package za.co.sfh.stocklistener.signals;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * In-memory queue of confirmed breakout signals waiting to be picked up by the
 * TradingView scheduled task via GET /api/signals/pending.
 *
 * Thread-safe: the Ollama async callbacks write to it from virtual threads while
 * the HTTP layer reads from it on the Tomcat thread pool.
 */
@Slf4j
@Component
public class SignalStore {

    private final ConcurrentLinkedQueue<BreakoutSignal> pending = new ConcurrentLinkedQueue<>();

    /**
     * Adds a confirmed signal to the pending queue.
     */
    public void add(BreakoutSignal signal) {
        pending.offer(signal);
        log.info("📥 Signal queued for TradingView alert: [{}] entry={} confidence={}%",
                signal.symbol(), signal.entry(), signal.confidence());
    }

    /**
     * Returns a snapshot of all pending signals without removing them.
     * The scheduled task reads this, fires alerts, then calls {@link #ackAll()}.
     */
    public List<BreakoutSignal> peekAll() {
        return List.copyOf(pending);
    }

    /**
     * Clears all pending signals after the scheduled task has fired the TradingView alerts.
     */
    public void ackAll() {
        int count = pending.size();
        pending.clear();
        if (count > 0) {
            log.info("✅ Acknowledged {} signal(s) — queue cleared", count);
        }
    }

    public int size() {
        return pending.size();
    }
}
