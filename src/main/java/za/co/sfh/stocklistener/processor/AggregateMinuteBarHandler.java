package za.co.sfh.stocklistener.processor;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import za.co.sfh.stocklistener.payloads.AggregateMinuteBar;
import za.co.sfh.stocklistener.processor.states.SymbolState;
import za.co.sfh.stocklistener.signals.BreakoutSignal;
import za.co.sfh.stocklistener.signals.SignalStore;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
@RequiredArgsConstructor
public class AggregateMinuteBarHandler implements MessageHandler {

    private final ObjectMapper objectMapper;
    private final OllamaBreakoutAnalyser ollamaAnalyser;
    private final SignalStore signalStore;
    private final ConcurrentHashMap<String, SymbolState> stateMap = new ConcurrentHashMap<>();

    @Value("${filter.min-close}")
    private double minClose;

    @Value("${filter.min-volume}")
    private long minVolume;

    @Value("${ollama.breakout.min-confidence:70}")
    private int minConfidence;

    public Optional<SymbolState> getState(String symbol) {
        return Optional.ofNullable(stateMap.get(symbol));
    }

    @Override
    public boolean supports(String eventType) {
        return "AM".equals(eventType);
    }

    @Override
    public void handle(JsonNode node) {
        AggregateMinuteBar bar = objectMapper.convertValue(node, AggregateMinuteBar.class);
        log.debug("{}", bar);

        // Pre-filter: ignore low-price and low-volume symbols
        // Vol is 50 000

        if (bar.close() < minClose || bar.volume() < minVolume) {
            return;
        }

        if (bar.high()< bar.low()){
            log.debug("No interested in shorting at the moment: [symbol: {}; high: {}; low: {}]", bar.symbol(), bar.high(), bar.low());
            return;
        }

        SymbolState state = stateMap.computeIfAbsent(bar.symbol(), s -> new SymbolState());
        state.addBar(bar);
        log.debug("Statemap size: {}", stateMap.size());

        if (state.isBreakout(bar)) {
            log.info("[{}] Breakout candidate detected — sending to Ollama for analysis", bar.symbol());

            ollamaAnalyser.analyseAsync(
                    bar.symbol(),
                    state.getCandles(),
                    state.getAvgRange(),
                    state.getAvgVolume()
            ).thenAccept(analysis -> {

                log.info("Ollama analysis: [bar symbol:{}; analysis: {}]", bar.symbol(), analysis);

                if (analysis.confirmed() && analysis.confidence() >= minConfidence) {
                    log.info("🚀 SIGNAL [{}] confidence={}% entry={} stop={} target={} risk={} | {}",
                            bar.symbol(), analysis.confidence(),
                            analysis.entry(), analysis.stop(), analysis.target(),
                            analysis.risk(), analysis.notes());

                    signalStore.add(new BreakoutSignal(
                            UUID.randomUUID().toString(),
                            bar.symbol(),
                            analysis.entry(),
                            analysis.stop(),
                            analysis.target(),
                            analysis.confidence(),
                            analysis.risk(),
                            analysis.notes(),
                            System.currentTimeMillis()
                    ));
                } else {
                    log.info("[{}] Ollama rejected candidate — confidence={}% confirmed={}",
                            bar.symbol(), analysis.confidence(), analysis.confirmed());
                }
            });
        }
    }
}
