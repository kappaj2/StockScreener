package za.co.sfh.stocklistener.processor.states;

import za.co.sfh.stocklistener.payloads.AggregateMinuteBar;

import java.time.LocalDate;
import java.util.List;

/**
 * Serializable snapshot of {@link SymbolState} stored in Redis.
 * Contains only the scalar accumulators and the last N candles —
 * enough to resume all indicators after a restart without replaying history.
 */
public record SymbolStateSnapshot(
        String symbol,
        double avgRange,
        double avgVolume,
        double preMarketHigh,
        double preMarketLow,
        double cumulativePV,
        double cumulativeVolume,
        double vwap,
        LocalDate sessionDate,
        double ema9,
        int totalBars,
        double emaSeedSum,
        List<AggregateMinuteBar> candles,
        List<Double> vwapHistory
) {}
