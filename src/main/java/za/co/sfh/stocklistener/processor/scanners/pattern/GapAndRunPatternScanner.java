package za.co.sfh.stocklistener.processor.scanners.pattern;

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

    @Value("${patterns.gap-and-run.min-gap-pct:2.0}")
    private double minGapPct;

    // Current bar's body must be >= this fraction of its own range (0.5 = 50%)
    // Rejects spinning tops / doji that happened to gap up
    @Value("${patterns.gap-and-run.min-body-ratio:0.5}")
    private double minBodyRatio;

    // Prev bar's close must be in the top X% of its range — base candle must be strong
    @Value("${patterns.gap-and-run.prev-close-top-pct:0.30}")
    private double prevCloseTopPct;

    // Volume on the gap bar must be >= this multiple of avgVolume
    @Value("${patterns.gap-and-run.min-volume-multiplier:3.0}")
    private double minVolumeMultiplier;

    @Value("${patterns.gap-and-run.stop:0.92}")
    private double stopMultiplier;

    @Value("${patterns.gap-and-run.target:1.10}")
    private double targetMultiplier;

    @Value("${patterns.gap-and-run.storeSignal:true}")
    private boolean storeSignal;

    // Minimum total session volume traded — filters illiquid stocks where avg-based gates are meaningless
    @Value("${patterns.gap-and-run.min-cumulative-volume:500000}")
    private long minCumulativeVolume;

    // Sustained volume: look back this many bars and require at least sustainedVolumeMinBars of them
    // to have volume >= avgVolume — catches "only 1-2 spike" patterns
    @Value("${patterns.gap-and-run.sustained-volume-lookback:5}")
    private int sustainedVolumeLookback;

    @Value("${patterns.gap-and-run.sustained-volume-min-bars:2}")
    private int sustainedVolumeMinBars;

    // When true, the gap bar must open above the pre-market high — highest-quality GAR signals only
    @Value("${patterns.gap-and-run.require-above-premarket-high:false}")
    private boolean requireAbovePremarketHigh;

    @Value("${patterns.gap-and-run.enabled:true}")
    private boolean enabled;

    @Override
    public boolean shouldStore() {
        return storeSignal;
    }

    @Override
    public Optional<BreakoutSignal> scan(AggregateMinuteBar bar, SymbolState state) {
        if (!enabled) return Optional.empty();

        if (state.getCandles().size() < 2) return Optional.empty();

        AggregateMinuteBar[] arr = state.getCandles().toArray(new AggregateMinuteBar[0]);
        AggregateMinuteBar prev = arr[arr.length - 2];

        // Both bars must be bullish
        if (prev.close() <= prev.open()) return Optional.empty();
        if (bar.close() <= bar.open())   return Optional.empty();

        // Gap up: current open must be strictly above prev close
        if (bar.open() <= prev.close()) return Optional.empty();

        double gapPct = (bar.open() - prev.close()) / prev.close() * 100;
        if (gapPct < minGapPct) return Optional.empty();

        // Gap must hold: low must not undercut prev close (filled gap = failed gap)
        if (bar.low() < prev.close()) {
            log.debug("Gap and Run rejected — gap filled intra-bar [symbol: {}; barLow: {}; prevClose: {}]",
                    bar.symbol(), bar.low(), prev.close());
            return Optional.empty();
        }

        if (!prevBarIsStrong(bar.symbol(), prev)) return Optional.empty();

        double bodyRatio = bodyRatio(bar);
        if (bodyRatio < 0) return Optional.empty();
        if (bodyRatio < minBodyRatio) {
            log.debug("Gap and Run rejected — body too small relative to range [symbol: {}; bodyRatio: {}; required: {}]",
                    bar.symbol(), bodyRatio, minBodyRatio);
            return Optional.empty();
        }

        if (!volumeIsValid(bar, state, arr)) return Optional.empty();

        // Optional: gap bar must open above pre-market high — strongest quality filter
        if (requireAbovePremarketHigh && state.getPreMarketHigh() > 0 && bar.open() <= state.getPreMarketHigh()) {
            log.debug("Gap and Run rejected — open did not clear pre-market high [symbol: {}; open: {}; pmHigh: {}]",
                    bar.symbol(), bar.open(), state.getPreMarketHigh());
            return Optional.empty();
        }

        log.info("Gap and Run [symbol: {}; gapPct: {}; bodyRatio: {}; volume: {}; avgVolume: {}; cumulativeVolume: {}]",
                bar.symbol(), gapPct, bodyRatio, bar.volume(), state.getAvgVolume(), state.getCumulativeVolume());

        return Optional.of(BreakoutSignal.withoutRvol(
                UUID.randomUUID().toString(),
                bar.symbol(),
                PatternType.GAP_AND_RUN,
                bar.close(),
                bar.close() * stopMultiplier,
                bar.close() * targetMultiplier,
                100,
                "unknown",
                String.format("gap %.2f%% body %.0f%% of range gapHeld prevStrong", gapPct, bodyRatio * 100),
                bar.endTimestampMs().toInstant().toEpochMilli(),
                state.getPreMarketHigh(),
                state.getPreMarketLow(),
                null,
                false
        ));
    }

    private boolean prevBarIsStrong(String symbol, AggregateMinuteBar prev) {
        double prevRange = prev.high() - prev.low();
        if (prevRange <= 0) return true; // doji — let other checks decide
        double prevClosePosition = (prev.high() - prev.close()) / prevRange;
        if (prevClosePosition > prevCloseTopPct) {
            log.debug("Gap and Run rejected — prev bar closed too far from its high [symbol: {}; closePosition: {}; threshold: {}]",
                    symbol, prevClosePosition, prevCloseTopPct);
            return false;
        }
        return true;
    }

    /** Returns body/range ratio, or -1 if the bar range is zero. */
    private double bodyRatio(AggregateMinuteBar bar) {
        double barRange = bar.high() - bar.low();
        if (barRange <= 0) return -1;
        return (bar.close() - bar.open()) / barRange;
    }

    private boolean volumeIsValid(AggregateMinuteBar bar, SymbolState state, AggregateMinuteBar[] arr) {
        if (bar.volume() < minVolumeMultiplier * state.getAvgVolume()) {
            log.debug("Gap and Run rejected — volume insufficient [symbol: {}; volume: {}; required: {}×avg={}]",
                    bar.symbol(), bar.volume(), minVolumeMultiplier, minVolumeMultiplier * state.getAvgVolume());
            return false;
        }
        if (state.getCumulativeVolume() < minCumulativeVolume) {
            log.debug("Gap and Run rejected — cumulative session volume too low [symbol: {}; cumulativeVolume: {}; required: {}]",
                    bar.symbol(), state.getCumulativeVolume(), minCumulativeVolume);
            return false;
        }
        int lookback = Math.min(sustainedVolumeLookback, arr.length - 1);
        int elevatedCount = 0;
        for (int i = arr.length - 1 - lookback; i < arr.length - 1; i++) {
            if (arr[i].volume() >= state.getAvgVolume()) elevatedCount++;
        }
        if (elevatedCount < sustainedVolumeMinBars) {
            log.debug("Gap and Run rejected — volume not sustained [symbol: {}; elevatedBars: {}/{}; required: {}]",
                    bar.symbol(), elevatedCount, lookback, sustainedVolumeMinBars);
            return false;
        }
        return true;
    }
}