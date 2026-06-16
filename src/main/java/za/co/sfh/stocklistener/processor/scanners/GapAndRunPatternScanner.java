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

@Slf4j
@Component
public class GapAndRunPatternScanner implements PatternScanner {

    @Value("${patterns.gap-and-run.min-gap-pct:0.5}")
    private double minGapPct;

    // Current bar's body must be >= this factor × previous bar's range (1.5 = 150%)
    @Value("${patterns.gap-and-run.body-factor:1.5}")
    private double bodyFactor;

    @Value("${patterns.gap-and-run.stop:0.92}")
    private double stopMultiplier;

    @Value("${patterns.gap-and-run.target:1.10}")
    private double targetMultiplier;

    @Value("${patterns.gap-and-run.storeSignal:true}")
    private boolean storeSignal;

    // Minimum total session volume traded — filters illiquid stocks where 2× avg is meaningless
    @Value("${patterns.gap-and-run.min-cumulative-volume:500000}")
    private long minCumulativeVolume;

    // Sustained volume: look back this many bars and require at least sustainedVolumeMinBars of them
    // to have volume >= avgVolume — catches "only 1-2 spike" patterns
    @Value("${patterns.gap-and-run.sustained-volume-lookback:5}")
    private int sustainedVolumeLookback;

    @Value("${patterns.gap-and-run.sustained-volume-min-bars:2}")
    private int sustainedVolumeMinBars;

    @Override
    public boolean shouldStore() {
        return storeSignal;
    }

    @Override
    public Optional<BreakoutSignal> scan(AggregateMinuteBar bar, SymbolState state) {
        if (state.getCandles().size() < 2) return Optional.empty();

        AggregateMinuteBar[] arr = state.getCandles().toArray(new AggregateMinuteBar[0]);
        AggregateMinuteBar prev = arr[arr.length - 2];

        if (prev.close() <= prev.open()) return Optional.empty();    // prev bar must be bullish
        if (bar.open() <= prev.close()) return Optional.empty();    // must gap up

        double gapPct = (bar.open() - prev.close()) / prev.close() * 100;
        if (gapPct < minGapPct) return Optional.empty();

        double body = bar.close() - bar.open();
        double prevRange = prev.high() - prev.low();

        if (body <= 0 || prevRange <= 0) return Optional.empty();   // current bar must be bullish

        if (body < bodyFactor * prevRange) {
            log.debug("Gap and Run body check failed [symbol: {}; gapPct: {}; body: {}; prevRange: {}; required: {}]",
                    bar.symbol(), gapPct, body, prevRange, bodyFactor * prevRange);
            return Optional.empty();
        }

        if (bar.volume() <= 2 * state.getAvgVolume()) return Optional.empty();  // volume confirmation

        // Reject illiquid stocks: total session volume must clear a minimum floor
        if (state.getCumulativeVolume() < minCumulativeVolume) {
            log.debug("Gap and Run rejected — cumulative session volume too low [symbol: {}; cumulativeVolume: {}; required: {}]",
                    bar.symbol(), state.getCumulativeVolume(), minCumulativeVolume);
            return Optional.empty();
        }

        // Reject "one or two spike" patterns: require sustained elevated volume over recent bars
        int lookback = Math.min(sustainedVolumeLookback, arr.length - 1); // exclude trigger bar (last)
        int elevatedCount = 0;
        for (int i = arr.length - 1 - lookback; i < arr.length - 1; i++) {
            if (arr[i].volume() >= state.getAvgVolume()) elevatedCount++;
        }
        if (elevatedCount < sustainedVolumeMinBars) {
            log.debug("Gap and Run rejected — volume not sustained [symbol: {}; elevatedBars: {}/{}; required: {}]",
                    bar.symbol(), elevatedCount, lookback, sustainedVolumeMinBars);
            return Optional.empty();
        }

        double ratio = body / prevRange;
        log.info("Gap and Run [symbol: {}; gapPct: {}; bodyRatio: {}; volume: {}; avgVolume: {}; cumulativeVolume: {}; sustainedBars: {}/{}]",
                bar.symbol(), gapPct, ratio, bar.volume(), state.getAvgVolume(), state.getCumulativeVolume(), elevatedCount, lookback);

        return Optional.of(new BreakoutSignal(
                UUID.randomUUID().toString(),
                bar.symbol(),
                PatternType.GAP_AND_RUN,
                bar.close(),
                bar.close() * stopMultiplier,
                bar.close() * targetMultiplier,
                100,
                "unknown",
                String.format("gap %.2f%% body %.2f×prev-range", gapPct, ratio),
                bar.endTimestampMs().toInstant().toEpochMilli(),
                state.getPreMarketHigh(),
                state.getPreMarketLow(),
                null,
                false
        ));
    }
}