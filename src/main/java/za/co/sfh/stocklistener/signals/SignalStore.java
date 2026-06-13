package za.co.sfh.stocklistener.signals;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import za.co.sfh.stocklistener.persistence.entities.SignalHistoryEntity;
import za.co.sfh.stocklistener.persistence.repositories.SignalHistoryRepository;

import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory store of confirmed breakout signals waiting to be picked up by the
 * TradingView scheduled task via GET /api/signals/pending.
 *
 * Thread-safe: the Ollama async callbacks write to it from virtual threads while
 * the HTTP layer reads from it on the Tomcat thread pool.
 *
 * Every signal added via {@link #add(BreakoutSignal)} is also persisted to the
 * {@code signal_history} table so that signals survive an app restart or the
 * in-memory queue being cleared. History is append-only and is not purged.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SignalStore {

    private final ConcurrentHashMap<String, BreakoutSignal> pending = new ConcurrentHashMap<>();

    private final SignalHistoryRepository signalHistoryRepository;

    /**
     * Adds a confirmed signal to the pending store and records it in the
     * persistent signal history.
     */
    public void add(BreakoutSignal signal) {
        pending.put(signal.id(), signal);
        log.info("📥 Signal queued for TradingView alert: [{}] entry={} confidence={}%",
                signal.symbol(), signal.entry(), signal.confidence());
        persistHistory(signal);
    }

    private void persistHistory(BreakoutSignal signal) {
        try {
            signalHistoryRepository.save(SignalHistoryEntity.from(signal));
        } catch (Exception e) {
            log.error("Failed to persist signal history for [{}] id={}", signal.symbol(), signal.id(), e);
        }
    }

    /**
     * Returns a snapshot of all pending signals sorted by timestamp, without removing them.
     * The scheduled task reads this, fires alerts, then calls {@link #ackAll()}.
     */
    public List<BreakoutSignal> peekAll() {
        return pending.values().stream()
                .sorted(Comparator.comparingLong(BreakoutSignal::timestamp))
                .toList();
    }

    /**
     * Clears all pending signals after the scheduled task has fired the TradingView alerts.
     */
    public void ackAll() {
        int count = pending.size();
        pending.clear();
        if (count > 0) {
            log.info("✅ Acknowledged {} signal(s) — store cleared", count);
        }
    }

    /**
     * Removes a single signal by its id.
     */
    public void remove(String id) {
        pending.remove(id);
    }

    /**
     * Updates the news headline for a signal identified by id.
     * Called by the external news scanner via PUT /api/signals/{id}/news.
     */
    public void updateNews(String id, String news) {
        pending.computeIfPresent(id, (k, s) -> new BreakoutSignal(
                s.id(), s.symbol(), s.pattern(), s.entry(), s.stop(), s.target(),
                s.confidence(), s.risk(), s.notes(), s.timestamp(),
                s.preMarketHigh(), s.preMarketLow(), news, s.highWatch()
        ));
    }

    public int size() {
        return pending.size();
    }
}
