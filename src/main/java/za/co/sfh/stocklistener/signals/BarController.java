package za.co.sfh.stocklistener.signals;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import za.co.sfh.stocklistener.payloads.AggregateMinuteBar;
import za.co.sfh.stocklistener.processor.AggregateMinuteBarHandler;

import java.util.List;

/**
 * Exposes the in-memory candle history for a tracked symbol.
 *
 * Endpoints:
 *   GET /api/bars/{symbol}  — returns the last N minute bars for the symbol, or 404 if not tracked.
 */
@Slf4j
@RestController
@RequestMapping("/api/bars")
@RequiredArgsConstructor
public class BarController {

    private final AggregateMinuteBarHandler aggregateMinuteBarHandler;

    @GetMapping("/{symbol}")
    public ResponseEntity<List<AggregateMinuteBar>> bars(@PathVariable String symbol) {
        log.debug("GET /api/bars/{}", symbol);
        return aggregateMinuteBarHandler.getState(symbol.toUpperCase())
                .map(state -> ResponseEntity.ok(state.getCandles()))
                .orElse(ResponseEntity.notFound().build());
    }
}
