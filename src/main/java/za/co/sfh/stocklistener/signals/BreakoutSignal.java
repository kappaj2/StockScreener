package za.co.sfh.stocklistener.signals;

/**
 * A confirmed signal produced by a pattern scanner.
 * Queued in {@link SignalStore} until the TradingView scheduled task drains it
 * via GET /api/signals/pending and fires the TradingView alert.
 */
public record BreakoutSignal(
        String id,              // UUID — used to idempotency-check duplicate alerts
        String symbol,          // e.g. "AAPL"
        PatternType pattern,    // e.g. BREAKOUT, INVERTED_VEE
        double entry,         // suggested entry price
        double stop,          // stop-loss price
        double target,        // price target
        int    confidence,    // 0–100
        String risk,          // "low" | "medium" | "high"
        String notes,         // rationale
        long   timestamp,     // epoch ms when signal was generated
        double preMarketHigh, // pre-/after-market high at signal time
        double preMarketLow,  // pre-/after-market low at signal time
        String news           // latest relevant news headline (updated via PUT /api/signals/{id}/news)
) {}
