package za.co.sfh.stocklistener.ibkr;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * REST API for the IBKR real-time tape.
 *
 * <pre>
 *   GET    /api/tape/{symbol}            — last N prints for a symbol
 *   POST   /api/tape/subscribe/{symbol}  — start streaming a symbol
 *   DELETE /api/tape/subscribe/{symbol}  — stop streaming and clear buffer
 *   GET    /api/tape/symbols             — list all subscribed symbols
 *   GET    /api/tape/status              — connection status
 * </pre>
 */
@RestController
@RequestMapping("/api/tape")
@RequiredArgsConstructor
public class IbkrTapeController {

    private final IbkrTapeClient tapeClient;
    private final IbkrTickStore  tickStore;

    /**
     * Returns the rolling tape buffer for {@code symbol} (oldest first).
     * Returns 404 if the symbol has never been subscribed or has no ticks yet.
     */
    @GetMapping("/{symbol}")
    public ResponseEntity<List<IbkrTickEvent>> getTape(@PathVariable String symbol) {
        var ticks = tickStore.getTicks(symbol.toUpperCase());
        return ticks.isEmpty()
                ? ResponseEntity.notFound().build()
                : ResponseEntity.ok(ticks);
    }

    /**
     * Subscribe to real-time time-and-sales for {@code symbol}.
     * Returns 503 if not connected to TWS.
     */
    @PostMapping("/subscribe/{symbol}")
    public ResponseEntity<String> subscribe(@PathVariable String symbol) {
        if (!tapeClient.isConnected()) {
            return ResponseEntity.status(503).body("Not connected to IBKR TWS — check ibkr.enabled and that TWS is running");
        }
        tapeClient.subscribe(symbol.toUpperCase());
        return ResponseEntity.ok("Subscribed to " + symbol.toUpperCase());
    }

    /**
     * Unsubscribe from a symbol and clear its tick buffer.
     */
    @DeleteMapping("/subscribe/{symbol}")
    public ResponseEntity<String> unsubscribe(@PathVariable String symbol) {
        tapeClient.unsubscribe(symbol.toUpperCase());
        return ResponseEntity.ok("Unsubscribed from " + symbol.toUpperCase());
    }

    /** All symbols currently streaming. */
    @GetMapping("/symbols")
    public Set<String> subscribedSymbols() {
        return tapeClient.subscribedSymbols();
    }

    /** Quick health-check for the TWS connection. */
    @GetMapping("/status")
    public Map<String, Object> status() {
        return Map.of(
                "connected",  tapeClient.isConnected(),
                "symbols",    tapeClient.subscribedSymbols(),
                "tickCounts", tapeClient.subscribedSymbols().stream()
                        .collect(java.util.stream.Collectors.toMap(
                                s -> s,
                                s -> tickStore.getTicks(s).size()
                        ))
        );
    }
}
