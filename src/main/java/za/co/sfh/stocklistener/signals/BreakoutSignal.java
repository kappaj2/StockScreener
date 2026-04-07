package za.co.sfh.stocklistener.signals;

/**
 * A confirmed breakout signal produced by the Ollama analysis pipeline.
 * Queued in {@link SignalStore} until the TradingView scheduled task drains it
 * via GET /api/signals/pending and fires the TradingView alert.
 */
public record BreakoutSignal(
        String id,          // UUID — used to idempotency-check duplicate alerts
        String symbol,      // e.g. "AAPL"
        double entry,       // Candle 2 open price
        double stop,        // 8% below entry
        double target,      // 50% above entry
        int    confidence,  // Ollama confidence 0–100
        String risk,        // "low" | "medium" | "high"
        String notes,       // Ollama rationale
        long   timestamp    // epoch ms when signal was generated
) {}
