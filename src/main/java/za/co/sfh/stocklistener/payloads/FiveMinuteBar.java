package za.co.sfh.stocklistener.payloads;

import java.time.ZonedDateTime;

/**
 * Synthetic 5-minute OHLCV candle built by aggregating five consecutive 1-minute bars.
 * Emitted by {@link za.co.sfh.stocklistener.processor.aggregators.FiveMinuteBarAggregator}
 * once the 5th bar of each boundary-aligned window completes.
 *
 * <p>VWAP is computed as the volume-weighted average of each minute bar's typical price
 * ((H+L+C)/3 × volume), divided by total window volume.
 */
public record FiveMinuteBar(
        String symbol,
        double open,
        double high,
        double low,
        double close,
        long   volume,
        double vwap,
        ZonedDateTime startTime,   // bar-open time (minute 0 of the 5-min window)
        ZonedDateTime endTime,     // bar-close time (end of minute 4 of the 5-min window)
        double rvol                // relative volume vs same time slot on the previous trading day
) {
    /** Convenience factory — used when RVOL is not yet available (e.g. first session). */
    public static FiveMinuteBar withoutRvol(String symbol, double open, double high, double low,
                                            double close, long volume, double vwap,
                                            ZonedDateTime startTime, ZonedDateTime endTime) {
        return new FiveMinuteBar(symbol, open, high, low, close, volume, vwap, startTime, endTime, Double.NaN);
    }

    public boolean hasRvol() {
        return !Double.isNaN(rvol);
    }

    public double range() {
        return high - low;
    }

    public double body() {
        return Math.abs(close - open);
    }

    public double upperWick() {
        return high - Math.max(open, close);
    }

    public double lowerWick() {
        return Math.min(open, close) - low;
    }

    public boolean isGreen() {
        return close >= open;
    }

    public boolean isRed() {
        return close < open;
    }

    /**
     * Same classification logic as {@link AggregateMinuteBar#johnWickType()}.
     * BULLISH = long lower wick (hammer); BEARISH = long upper wick (shooting star).
     */
    public JohnWickType johnWickType() {
        double range = range();
        if (range <= 0) return JohnWickType.NONE;

        double bodyRatio      = body()      / range;
        double upperWickRatio = upperWick() / range;
        double lowerWickRatio = lowerWick() / range;

        if (upperWickRatio >= 0.60
                && bodyRatio <= 0.25
                && (close - low) / range <= 0.20
                && (open  - low) / range <= 0.30) {
            return JohnWickType.BEARISH;
        }

        if (lowerWickRatio >= 0.60
                && bodyRatio <= 0.25
                && (high - close) / range <= 0.20
                && (high - open)  / range <= 0.30) {
            return JohnWickType.BULLISH;
        }

        return JohnWickType.NONE;
    }
}