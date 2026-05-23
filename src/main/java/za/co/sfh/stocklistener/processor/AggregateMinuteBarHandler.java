package za.co.sfh.stocklistener.processor;

import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import za.co.sfh.stocklistener.payloads.AggregateMinuteBar;
import za.co.sfh.stocklistener.persistence.entities.MinuteBarEntity;
import za.co.sfh.stocklistener.persistence.repositories.MinuteBarRepository;
import za.co.sfh.stocklistener.processor.scanners.DontDiddleInTheMiddle;
import za.co.sfh.stocklistener.processor.states.SymbolState;
import za.co.sfh.stocklistener.processor.stockwatch.HighWatchStockService;
import za.co.sfh.stocklistener.signals.BreakoutSignal;
import za.co.sfh.stocklistener.signals.SignalStore;

import java.time.Duration;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@RequiredArgsConstructor
public class AggregateMinuteBarHandler implements MessageHandler {

    private final ObjectMapper objectMapper;
    private final SignalStore signalStore;
    private final List<PatternScanner> scanners;
    private final DontDiddleInTheMiddle dontDiddleInTheMiddle;
    private final MinuteBarRepository minuteBarRepository;
    private final HighWatchStockService highWatchStockService;
    private final ConcurrentHashMap<String, SymbolState> stateMap = new ConcurrentHashMap<>();
    private final ExecutorService persistenceExecutor = Executors.newSingleThreadExecutor();

    private static final ZoneId ET = ZoneId.of("America/New_York");
    private static final LocalTime MARKET_OPEN = LocalTime.of(9, 30);

    @Value("${filter.min-close}")
    private double minClose;

    @Value("${filter.max-close}")
    private double maxClose;

    @Value("${filter.min-volume}")
    private long minVolume;

    @Value("${filter.min-avg-daily-volume}")
    private long minAvgDailyVolume;

    public Optional<SymbolState> getState(String symbol) {
        return Optional.ofNullable(stateMap.get(symbol));
    }

    @PreDestroy
    void shutdownPersistenceExecutor() {
        persistenceExecutor.shutdown();
        try {
            if (!persistenceExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                persistenceExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            persistenceExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public boolean supports(String eventType) {
        return "AM".equals(eventType);
    }

    @Override
    public void handle(JsonNode node) {
        var bar = objectMapper.convertValue(node, AggregateMinuteBar.class);

        if (bar.close() < minClose || bar.close() > maxClose || bar.volume() < minVolume) {
            log.debug("[{}] Filtered by price/volume thresholds: close={} volume={}", bar.symbol(), bar.close(), bar.volume());
            return;
        }

        if (avgDailyVolumePerMinute(bar) < minAvgDailyVolume) {
            log.debug("[{}] Filtered by avg daily volume: accumulatedVolume={}", bar.symbol(), bar.accumulatedVolume());
            return;
        }

        //  Only record and screen green bars for long positions.
        if (bar.high() < bar.low()) {
            return;
        }

        persistMinuteBarAsync(bar);

        var state = stateMap.computeIfAbsent(bar.symbol(), s -> new SymbolState());

        state.addBar(bar);

        log.debug("Statemap size: {}", stateMap.size());

        for (PatternScanner scanner : scanners) {
            Optional<BreakoutSignal> signal = scanner.scan(bar, state);
            signal.ifPresent(s -> {
                log.info("[{}] {} pattern detected — entry={}", s.symbol(), s.pattern(), s.entry());
                if (scanner.shouldStore()) {
                    if (dontDiddleInTheMiddle.isInMiddle(s.entry(), state)) {
                        log.info("[{}] {} signal suppressed — entry={} is in the middle of yesterday's range",
                                s.symbol(), s.pattern(), s.entry());
                        return;
                    }
                    boolean onHighWatch = highWatchStockService.findBySymbol(s.symbol()).isPresent();
                    BreakoutSignal toStore = onHighWatch
                            ? new BreakoutSignal(s.id(), s.symbol(), s.pattern(), s.entry(), s.stop(),
                                    s.target(), s.confidence(), s.risk(), s.notes(), s.timestamp(),
                                    s.preMarketHigh(), s.preMarketLow(), s.news(), true)
                            : s;
                    if (onHighWatch) {
                        log.info("[{}] HIGH WATCH match — {} → displaying as HW_{}", s.symbol(), s.pattern(), s.pattern().getCode());
                    }
                    signalStore.add(toStore);
                }
            });
        }
    }

    /**
     * Returns the average per-minute volume for the current session, computed as
     * {@code accumulatedVolume / minutesElapsed} since market open (9:30 ET).
     * Returns {@link Long#MAX_VALUE} for pre-market bars so they are never filtered out
     * by the avg-daily-volume gate before the regular session has had a chance to build up.
     */
    private long avgDailyVolumePerMinute(AggregateMinuteBar bar) {
        LocalTime barTime = bar.startTimestampMs().withZoneSameInstant(ET).toLocalTime();
        if (!barTime.isAfter(MARKET_OPEN)) {
            return Long.MAX_VALUE;
        }
        long minutesElapsed = Duration.between(MARKET_OPEN, barTime).toMinutes() + 1;
        return bar.accumulatedVolume() / minutesElapsed;
    }

    private void persistMinuteBarAsync(AggregateMinuteBar bar) {
        persistenceExecutor.submit(() -> {
            try {
                minuteBarRepository.save(MinuteBarEntity.from(bar));
            } catch (Exception e) {
                log.warn("[{}] Failed to persist minute bar", bar.symbol(), e);
            }
        });
    }
}
