package za.co.sfh.stocklistener.processor;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import za.co.sfh.stocklistener.payloads.AggregateMinuteBar;
import za.co.sfh.stocklistener.processor.states.SymbolState;
import za.co.sfh.stocklistener.processor.states.SymbolStateRedisStore;
import za.co.sfh.stocklistener.signals.BreakoutSignal;
import za.co.sfh.stocklistener.signals.SignalStore;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
@RequiredArgsConstructor
public class AggregateMinuteBarHandler implements MessageHandler {

    private final ObjectMapper objectMapper;
    private final SignalStore signalStore;
    private final List<PatternScanner> scanners;
    private final SymbolStateRedisStore redisStore;
    private final ConcurrentHashMap<String, SymbolState> stateMap = new ConcurrentHashMap<>();

    @Value("${filter.min-close}")
    private double minClose;

    @Value("${filter.min-volume}")
    private long minVolume;

    public Optional<SymbolState> getState(String symbol) {
        return Optional.ofNullable(stateMap.get(symbol));
    }

    @Override
    public boolean supports(String eventType) {
        return "AM".equals(eventType);
    }

    @Override
    public void handle(JsonNode node) {
        var bar = objectMapper.convertValue(node, AggregateMinuteBar.class);

        //  Filter out small value and small volumes candles. Not screening those.
        if (bar.close() < minClose || bar.volume() < minVolume) {
            return;
        }

        //  Only record and screen green bars for long positions.
        if (bar.high() < bar.low()) {
            return;
        }

        var state = stateMap.computeIfAbsent(bar.symbol(),
                s -> redisStore.load(s).orElseGet(SymbolState::new));

        state.addBar(bar);

        redisStore.save(state);
        log.debug("Statemap size: {}", stateMap.size());

        for (PatternScanner scanner : scanners) {
            Optional<BreakoutSignal> signal = scanner.scan(bar, state);
            signal.ifPresent(s -> {
                log.info("[{}] {} pattern detected — entry={}", s.symbol(), s.pattern(), s.entry());
                if (scanner.shouldStore()) {
                    signalStore.add(s);
                }
            });
        }
    }
}
