package za.co.sfh.stocklistener.ibkr;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * In-memory rolling tape store — one queue per subscribed symbol.
 *
 * <p>Each queue is bounded to {@code ibkr.tape.max-ticks} entries (default 500).
 * Once the cap is reached the oldest print is dropped to make room for the newest,
 * giving a continuous sliding window of recent tape activity.
 */
@Component
public class IbkrTickStore {

    @Value("${ibkr.tape.max-ticks:500}")
    private int maxTicks;

    private final ConcurrentHashMap<String, ConcurrentLinkedQueue<IbkrTickEvent>> store =
            new ConcurrentHashMap<>();

    /** Append a tick, trimming the oldest entry if the window is full. */
    public void add(String symbol, IbkrTickEvent event) {
        var queue = store.computeIfAbsent(symbol, s -> new ConcurrentLinkedQueue<>());
        queue.offer(event);
        while (queue.size() > maxTicks) {
            queue.poll();
        }
    }

    /**
     * Returns an immutable snapshot of the tape for {@code symbol}, oldest first.
     * Returns an empty list if no ticks have been received yet.
     */
    public List<IbkrTickEvent> getTicks(String symbol) {
        var queue = store.get(symbol.toUpperCase());
        return queue == null ? List.of() : List.copyOf(queue);
    }

    /** Symbols that have at least one tick in the store. */
    public Set<String> subscribedSymbols() {
        return store.keySet();
    }

    /** Remove all buffered ticks for a symbol (e.g. after unsubscribing). */
    public void clear(String symbol) {
        store.remove(symbol.toUpperCase());
    }
}
