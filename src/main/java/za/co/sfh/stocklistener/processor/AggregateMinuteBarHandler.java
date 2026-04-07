package za.co.sfh.stocklistener.processor;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import za.co.sfh.stocklistener.payloads.AggregateMinuteBar;
import za.co.sfh.stocklistener.processor.states.SymbolState;

import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
@RequiredArgsConstructor
public class AggregateMinuteBarHandler implements MessageHandler {

    private final ObjectMapper objectMapper;
    private final ConcurrentHashMap<String, SymbolState> stateMap = new ConcurrentHashMap<>();

    @Value("${filter.min-close}")
    private double minClose;

    @Value("${filter.min-volume}")
    private long minVolume;

    @Override
    public boolean supports(String eventType) {
        return "AM".equals(eventType);
    }

    @Override
    public void handle(JsonNode node) {
        AggregateMinuteBar bar = objectMapper.convertValue(node, AggregateMinuteBar.class);
        log.info("{}", bar);

        /*
            Don't store symbols we are not interested in.
         */
        if (bar.close() < minClose || bar.volume() < minVolume) {
            return; // pre-filter
        }

        SymbolState state = stateMap.computeIfAbsent(
                bar.symbol(),
                s -> new SymbolState()
        );

        state.addBar(bar);
        log.debug("Statemap size: {}", stateMap.size());

        if (state.isBreakout(bar)) {
            // emitSignal(bar);
            log.debug("Wa have a candidate!");
        }
    }
}
