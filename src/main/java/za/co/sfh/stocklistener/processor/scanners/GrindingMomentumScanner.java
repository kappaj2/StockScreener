package za.co.sfh.stocklistener.processor.scanners;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import za.co.sfh.stocklistener.payloads.AggregateMinuteBar;
import za.co.sfh.stocklistener.processor.PatternScanner;
import za.co.sfh.stocklistener.processor.states.SymbolState;
import za.co.sfh.stocklistener.signals.BreakoutSignal;
import za.co.sfh.stocklistener.signals.PatternType;

import java.util.Optional;
import java.util.UUID;

/**
 * Detects a steady multi-bar price grind: a sustained step-up where no single bar
 * is explosive but the close advances consistently over the lookback window.
 *
 * Conditions (all must hold):
 *  1. Net close advance from base bar to current bar >= minAdvancePct
 *  2. At least minHigherCloses bars in the window closed above the previous bar's close
 *  3. No single bar in the window drops more than maxSingleDropPct from its open
 *  4. Current close is above the session average close
 */
@Slf4j
@Component
public class GrindingMomentumScanner implements PatternScanner {

    @Value("${patterns.grinding-momentum.lookback-bars:5}")
    private int lookbackBars;

    @Value("${patterns.grinding-momentum.min-advance-pct:6.0}")
    private double minAdvancePct;

    @Value("${patterns.grinding-momentum.min-higher-closes:3}")
    private int minHigherCloses;

    @Value("${patterns.grinding-momentum.max-single-drop-pct:2.0}")
    private double maxSingleDropPct;

    @Value("${patterns.grinding-momentum.stop:0.97}")
    private double stopMultiplier;

    @Value("${patterns.grinding-momentum.target:1.05}")
    private double targetMultiplier;

    @Value("${patterns.grinding-momentum.storeSignal:true}")
    private boolean storeSignal;

    // Only fire during regular market hours (9:30–16:00 ET) — pre-market grinds are noise
    @Value("${patterns.grinding-momentum.require-market-hours:true}")
    private boolean requireMarketHours;

    // Trigger bar volume must exceed avgVolume × this multiplier
    @Value("${patterns.grinding-momentum.min-volume-multiplier:1.5}")
    private double minVolumeMultiplier;

    // Minimum total session volume — rejects illiquid tickers
    @Value("${patterns.grinding-momentum.min-cumulative-volume:200000}")
    private long minCumulativeVolume;

    // Average volume across the lookback window must be >= avgVolume × this fraction (0 = disabled)
    @Value("${patterns.grinding-momentum.min-window-avg-volume-ratio:0.5}")
    private double minWindowAvgVolumeRatio;

    @Override
    public boolean shouldStore() {
        return storeSignal;
    }

    @Override
    public Optional<BreakoutSignal> scan(AggregateMinuteBar bar, SymbolState state) {
        // Need 1 base bar + lookbackBars window bars (current bar is the last window bar)
        if (state.getCandles().size() < lookbackBars + 1) return Optional.empty();

        // Reject pre-market / after-hours bars
        if (requireMarketHours && state.isOutsideNormalHours(bar.startTimestampMs())) {
            log.debug("Grinding momentum rejected — outside market hours [symbol: {}]", bar.symbol());
            return Optional.empty();
        }

        // Reject illiquid tickers
        if (state.getCumulativeVolume() < minCumulativeVolume) {
            log.debug("Grinding momentum rejected — cumulative volume too low [symbol: {}; cumulativeVolume: {}; required: {}]",
                    bar.symbol(), state.getCumulativeVolume(), minCumulativeVolume);
            return Optional.empty();
        }

        // Trigger bar must have meaningful volume
        if (minVolumeMultiplier > 0 && bar.volume() < minVolumeMultiplier * state.getAvgVolume()) {
            log.debug("Grinding momentum rejected — trigger bar volume too low [symbol: {}; volume: {}; required: {}×avg={}]",
                    bar.symbol(), bar.volume(), minVolumeMultiplier, minVolumeMultiplier * state.getAvgVolume());
            return Optional.empty();
        }

        AggregateMinuteBar[] arr = state.getCandles().toArray(new AggregateMinuteBar[0]);
        int n = arr.length;

        double baseClose = arr[n - lookbackBars - 1].close();
        if (baseClose <= 0) return Optional.empty();

        // 1. Net advance from base close to current close
        double netAdvancePct = (bar.close() - baseClose) / baseClose * 100;
        if (netAdvancePct < minAdvancePct) {
            log.debug("Grinding momentum advance check failed [symbol: {}; netAdvancePct: {}; required: {}]",
                    bar.symbol(), netAdvancePct, minAdvancePct);
            return Optional.empty();
        }

        // 2. Count bars where close > previous bar's close (step-up closes)
        int higherCloses = 0;
        for (int i = n - lookbackBars; i < n; i++) {
            if (arr[i].close() > arr[i - 1].close()) {
                higherCloses++;
            }
        }
        if (higherCloses < minHigherCloses) {
            log.debug("Grinding momentum higher-close check failed [symbol: {}; higherCloses: {}; required: {}]",
                    bar.symbol(), higherCloses, minHigherCloses);
            return Optional.empty();
        }

        // 3. No single bar in the window may drop more than maxSingleDropPct from its open
        for (int i = n - lookbackBars; i < n; i++) {
            AggregateMinuteBar b = arr[i];
            if (b.open() > 0) {
                double dropPct = (b.open() - b.close()) / b.open() * 100;
                if (dropPct > maxSingleDropPct) {
                    log.debug("Grinding momentum single-bar drop check failed [symbol: {}; idx: {}; dropPct: {}]",
                            bar.symbol(), i, dropPct);
                    return Optional.empty();
                }
            }
        }

        // 4. Current close must be above the session average close
        if (bar.close() <= state.getAvgClose()) return Optional.empty();

        // 5. Average volume across the lookback window must be sustained (not just the trigger bar)
        if (minWindowAvgVolumeRatio > 0 && state.getAvgVolume() > 0) {
            double windowVolumeSum = 0;
            for (int i = n - lookbackBars; i < n; i++) {
                windowVolumeSum += arr[i].volume();
            }
            double windowAvgVolume = windowVolumeSum / lookbackBars;
            if (windowAvgVolume < minWindowAvgVolumeRatio * state.getAvgVolume()) {
                log.debug("Grinding momentum rejected — window volume too thin [symbol: {}; windowAvgVolume: {}; required: {}×avg={}]",
                        bar.symbol(), windowAvgVolume, minWindowAvgVolumeRatio, minWindowAvgVolumeRatio * state.getAvgVolume());
                return Optional.empty();
            }
        }

        log.info("Grinding momentum [symbol: {}; netAdvancePct: {}; higherCloses: {}/{}; baseClose: {}; currentClose: {}; cumulativeVolume: {}]",
                bar.symbol(), netAdvancePct, higherCloses, lookbackBars, baseClose, bar.close(), state.getCumulativeVolume());

        return Optional.of(new BreakoutSignal(
                UUID.randomUUID().toString(),
                bar.symbol(),
                PatternType.GRINDING_MOMENTUM,
                bar.close(),
                bar.close() * stopMultiplier,
                bar.close() * targetMultiplier,
                100,
                "medium",
                String.format("%.2f%% grind over %d bars (%d/%d advanced)", netAdvancePct, lookbackBars, higherCloses, lookbackBars),
                bar.endTimestampMs().toInstant().toEpochMilli(),
                state.getPreMarketHigh(),
                state.getPreMarketLow(),
                null,
                false
        ));
    }
}
