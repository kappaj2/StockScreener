package za.co.sfh.stocklistener.payloads;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;
import tools.jackson.databind.annotation.JsonDeserialize;
import tools.jackson.databind.annotation.JsonSerialize;

import java.time.ZonedDateTime;

/**
 * Polygon Aggregate Minute Bar event — ev: "AM".
 * Fired at the end of each minute for every subscribed symbol.
 * <p>
 * Field reference: https://polygon.io/docs/stocks/ws_stocks_am
 */
public record AggregateMinuteBar(
        @JsonProperty("ev") String ev,       // event type, always "AM"
        @JsonProperty("sym") String symbol,   // ticker symbol, e.g. "AAPL"
        @JsonProperty("v") long volume,   // tick volume for this bar
        @JsonProperty("av") long accumulatedVolume, // accumulated volume since market open
        @JsonProperty("op") double officialOpen,      // today's official opening price
        @JsonProperty("vw") double vwap,              // VWAP for this bar
        @JsonProperty("o") double open,              // open price for this bar
        @JsonProperty("c") double close,             // close price for this bar
        @JsonProperty("h") double high,              // high price for this bar
        @JsonProperty("l") double low,               // low price for this bar
        @JsonProperty("a") double todayVwap,         // today's accumulated VWAP
        @JsonProperty("z") int avgTradeSize,      // average trade size
        @JsonSerialize(using = EpochMsSerializer.class)
        @JsonDeserialize(using = EpochMsDeserializer.class)
        @JsonProperty("s") ZonedDateTime startTimestampMs,  // bar start (ET)
        @JsonSerialize(using = EpochMsSerializer.class)
        @JsonDeserialize(using = EpochMsDeserializer.class)
        @JsonProperty("e") ZonedDateTime endTimestampMs     // bar end (ET)
) {
    // ── Structural candle metrics ─────────────────────────────────────────────

    /** Full range of the bar: H - L */
    @JsonProperty("range")
    public double range() {
        return high - low;
    }

    /** Size of the real body: |C - O| */
    @JsonProperty("body")
    public double body() {
        return Math.abs(close - open);
    }

    /** Upper wick: H - max(O, C) */
    @JsonProperty("upperWick")
    public double upperWick() {
        return high - Math.max(open, close);
    }

    /** Lower wick: min(O, C) - L */
    @JsonProperty("lowerWick")
    public double lowerWick() {
        return Math.min(open, close) - low;
    }

    // ── John Wick classification ──────────────────────────────────────────────

    /**
     * Classifies this candle as a John Wick pattern (strong wick rejection).
     *
     * <p>Thresholds used:
     * <ul>
     *   <li>Dominant wick / range  &ge; 0.60</li>
     *   <li>Body / range           &le; 0.25</li>
     *   <li>Close position ratio   &le; 0.20  (close near opposite end)</li>
     * </ul>
     *
     * @return {@link JohnWickType#BULLISH} for a long lower wick,
     *         {@link JohnWickType#BEARISH} for a long upper wick,
     *         {@link JohnWickType#NONE} otherwise.
     */
    @JsonProperty("johnWickType")
    public JohnWickType johnWickType() {
        double range = range();
        if (range <= 0) {
            return JohnWickType.NONE;
        }

        double bodyRatio      = body()      / range;
        double upperWickRatio = upperWick() / range;
        double lowerWickRatio = lowerWick() / range;

        // Bearish John Wick: dominant upper wick, close near low
        if (upperWickRatio >= 0.60
                && bodyRatio <= 0.25
                && (close - low) / range <= 0.20) {
            return JohnWickType.BEARISH;
        }

        // Bullish John Wick: dominant lower wick, close near high
        if (lowerWickRatio >= 0.60
                && bodyRatio <= 0.25
                && (high - close) / range <= 0.20) {
            return JohnWickType.BULLISH;
        }

        return JohnWickType.NONE;
    }
}
