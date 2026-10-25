package za.co.sfh.stocklistener.processor.aggregators;

import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import za.co.sfh.stocklistener.payloads.AggregateMinuteBar;
import za.co.sfh.stocklistener.payloads.FiveMinuteBar;
import za.co.sfh.stocklistener.persistence.entities.FiveMinuteBarEntity;
import za.co.sfh.stocklistener.persistence.repositories.FiveMinuteBarRepository;
import za.co.sfh.stocklistener.processor.indicators.RelativeVolumeIndicator;
import za.co.sfh.stocklistener.processor.scanners.scalping.ScalpingPatternScanner;
import za.co.sfh.stocklistener.processor.states.FiveMinuteState;
import za.co.sfh.stocklistener.signals.SignalStore;

import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Aggregates incoming 1-minute bars into 5-minute candles, one per symbol.
 *
 * <p>A new window starts on each boundary-aligned minute (09:30, 09:35, 09:40 …).
 * When the 5th bar of a window arrives the accumulator is flushed into a
 * {@link FiveMinuteBar}, added to the symbol's {@link FiveMinuteState}, and reset.
 *
 * <p>Bars outside market hours (before 09:30 ET or at/after 16:00 ET) are ignored.
 *
 * <p>The caller ({@link za.co.sfh.stocklistener.processor.AggregateMinuteBarHandler})
 * processes messages on a single virtual thread, so no internal locking is needed.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FiveMinuteBarAggregator {

    private static final ZoneId ET = ZoneId.of("America/New_York");
    private static final LocalTime MARKET_OPEN  = LocalTime.of(9, 30);
    private static final LocalTime MARKET_CLOSE = LocalTime.of(16, 0);

    private final List<ScalpingPatternScanner> scalpingScanner;
    private final SignalStore signalStore;
    private final FiveMinuteBarRepository fiveMinuteBarRepository;
    private final RelativeVolumeIndicator rvolIndicator;

    private final ConcurrentHashMap<String, FiveMinBarAccumulator> accumulators = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, FiveMinuteState>       stateMap     = new ConcurrentHashMap<>();
    private final ExecutorService persistenceExecutor = Executors.newSingleThreadExecutor();

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

    /**
     * Offer a 1-minute bar to the aggregator.
     * Returns the completed {@link FiveMinuteBar} if this bar closes a 5-minute window,
     * otherwise returns empty.
     */
    public Optional<FiveMinuteBar> offer(AggregateMinuteBar bar) {
        LocalTime barTime = bar.startTimestampMs().withZoneSameInstant(ET).toLocalTime();

        if (barTime.isBefore(MARKET_OPEN) || !barTime.isBefore(MARKET_CLOSE)) {
            return Optional.empty();
        }

        // Start a new window on each 5-minute boundary (minute % 5 == 0)
        int minute = barTime.getMinute();
        boolean isBoundary = (minute % 5) == 0;

        FiveMinBarAccumulator acc = accumulators.computeIfAbsent(bar.symbol(), s -> new FiveMinBarAccumulator());

        // If we hit a boundary and the accumulator already has bars from the previous window
        // (e.g. a gap or late start), flush what we have before opening the new window.
        if (isBoundary && acc.isComplete()) {
            acc.reset();
        } else if (isBoundary) {
            acc.reset();
        }

        acc.add(bar);

        if (acc.isComplete()) {
            FiveMinuteBar raw = acc.build();
            acc.reset();

            double rvol = computeRvol(raw);
            FiveMinuteBar fiveMin = new FiveMinuteBar(
                    raw.symbol(), raw.open(), raw.high(), raw.low(), raw.close(),
                    raw.volume(), raw.vwap(), raw.startTime(), raw.endTime(), rvol);

            FiveMinuteState state = stateMap.computeIfAbsent(fiveMin.symbol(), s -> new FiveMinuteState());
            state.addBar(fiveMin);

            log.debug("[{}] 5-min bar closed — O={} H={} L={} C={} V={} VWAP={} RVOL={}",
                    fiveMin.symbol(), fiveMin.open(), fiveMin.high(), fiveMin.low(),
                    fiveMin.close(), fiveMin.volume(), String.format("%.4f", fiveMin.vwap()),
                    fiveMin.hasRvol() ? String.format("%.2f", fiveMin.rvol()) : "n/a");

            persistFiveMinuteBarAsync(fiveMin);
            runScalpingScanners(fiveMin, state);

            return Optional.of(fiveMin);
        }

        return Optional.empty();
    }

    public Optional<FiveMinuteState> getState(String symbol) {
        return Optional.ofNullable(stateMap.get(symbol));
    }

    private double computeRvol(FiveMinuteBar bar) {
        try {
            return rvolIndicator.compute(bar);
        } catch (Exception e) {
            log.warn("[{}] RVOL computation failed", bar.symbol(), e);
            return Double.NaN;
        }
    }

    private void persistFiveMinuteBarAsync(FiveMinuteBar bar) {
        persistenceExecutor.submit(() -> {
            try {
                fiveMinuteBarRepository.save(FiveMinuteBarEntity.from(bar));
            } catch (Exception e) {
                log.warn("[{}] Failed to persist 5-minute bar", bar.symbol(), e);
            }
        });
    }

    private void runScalpingScanners(FiveMinuteBar bar, FiveMinuteState state) {
        for (ScalpingPatternScanner scanner : scalpingScanner) {
            try {
                scanner.scan(bar, state).ifPresent(signal -> {
                    log.info("[{}] Scalping signal: {} — entry={}", signal.symbol(), signal.pattern(), signal.entry());
                    signalStore.add(signal);
                });
            } catch (Exception e) {
                log.warn("[{}] Scalping scanner {} threw an exception", bar.symbol(), scanner.getClass().getSimpleName(), e);
            }
        }
    }
}
