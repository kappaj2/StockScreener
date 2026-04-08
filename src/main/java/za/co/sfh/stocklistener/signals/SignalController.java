package za.co.sfh.stocklistener.signals;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import za.co.sfh.stocklistener.processor.AggregateMinuteBarHandler;
import za.co.sfh.stocklistener.processor.states.SymbolState;

import java.util.List;

/**
 * Exposes pending breakout signals so the TradingView scheduled task can poll them
 * and fire alerts via mcp__tradingview__alert_create.
 *
 * Endpoints:
 *   GET  /api/signals/pending          — returns all queued signals as JSON (does NOT consume them)
 *   DELETE /api/signals/ack            — clears the queue after the scheduled task has fired all alerts
 *   GET  /api/signals/state/{symbol}   — returns the SymbolState for the given ticker
 */
@Slf4j
@RestController
@RequestMapping("/api/signals")
@RequiredArgsConstructor
public class SignalController {

    private final SignalStore signalStore;
    private final AggregateMinuteBarHandler aggregateMinuteBarHandler;

    /**
     * Returns all signals currently waiting to be fired as TradingView alerts.
     * Safe to call multiple times — does not consume the queue.
     */
    @GetMapping("/pending")
    public List<BreakoutSignal> pending() {
        List<BreakoutSignal> signals = signalStore.peekAll();
        log.debug("GET /api/signals/pending → {} signal(s)", signals.size());
        return signals;
    }

    /**
     * Acknowledge all signals — clears the queue.
     * The scheduled task calls this after it has successfully fired every TradingView alert.
     */
    @DeleteMapping("/ack")
    public void ack() {
        log.info("DELETE /api/signals/ack — clearing signal queue");
        signalStore.ackAll();
    }

    /**
     * Updates the news headline for a specific signal.
     * Called by the external news scanner when a relevant news item is found.
     */
    @PutMapping("/{id}/news")
    public ResponseEntity<Void> updateNews(@PathVariable String id, @RequestBody String news) {
        log.info("PUT /api/signals/{}/news — headline: {}", id, news);
        signalStore.updateNews(id, news);
        return ResponseEntity.ok().build();
    }

    /**
     * Returns the SymbolState for the given ticker symbol, or 404 if not yet tracked.
     */
    @GetMapping("/state/{symbol}")
    public ResponseEntity<SymbolState> state(@PathVariable String symbol) {
        log.debug("GET /api/signals/state/{}", symbol);
        return aggregateMinuteBarHandler.getState(symbol)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
}
