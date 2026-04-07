package za.co.sfh.stocklistener.payloads;

import com.fasterxml.jackson.annotation.JsonProperty;

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
        @JsonProperty("s") long startTimestampMs,  // bar start (epoch ms)
        @JsonProperty("e") long endTimestampMs     // bar end (epoch ms)
) {
}
