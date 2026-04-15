package za.co.sfh.stocklistener.ibkr;

/**
 * A single time-and-sales print received from IBKR TWS via
 * {@code reqTickByTickData("Last")}.
 *
 * <p>{@code timestamp} is epoch-seconds as supplied by IBKR (not milliseconds).
 * Multiply by 1000 to convert to epoch-ms for consistency with the rest of the app.
 */
public record IbkrTickEvent(
        String symbol,       // ticker, e.g. "AAPL"
        double price,        // execution price
        double size,         // shares traded in this print
        long   timestamp,    // epoch seconds (IBKR format)
        String exchange,     // executing exchange code, e.g. "ISLAND", "ARCA"
        String tickType,     // "LAST" | "BID_ASK"
        String conditions    // trade condition codes; empty string = regular trade
) {}
