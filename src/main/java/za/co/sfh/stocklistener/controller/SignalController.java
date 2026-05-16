package za.co.sfh.stocklistener.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
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
import za.co.sfh.stocklistener.signals.BreakoutSignal;
import za.co.sfh.stocklistener.signals.SignalStore;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/signals")
@RequiredArgsConstructor
@Tag(name = "Signals", description = "Breakout signal queue and per-symbol state")
public class SignalController {

    private final SignalStore signalStore;
    private final AggregateMinuteBarHandler aggregateMinuteBarHandler;

    @Operation(summary = "List pending signals",
            description = "Returns all breakout signals currently queued for alert delivery. Does not consume the queue.")
    @GetMapping("/pending")
    public List<BreakoutSignal> pending() {
        List<BreakoutSignal> signals = signalStore.peekAll();
        log.debug("GET /api/signals/pending → {} signal(s)", signals.size());
        return signals;
    }

    @Operation(summary = "Acknowledge all signals",
            description = "Clears the signal queue. Call after all pending alerts have been delivered.")
    @DeleteMapping("/ack")
    public void ack() {
        log.info("DELETE /api/signals/ack — clearing signal queue");
        signalStore.ackAll();
    }

    @Operation(summary = "Attach a news headline to a signal")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Headline attached"),
            @ApiResponse(responseCode = "404", description = "Signal ID not found")
    })
    @PutMapping("/{id}/news")
    public ResponseEntity<Void> updateNews(
            @Parameter(description = "Signal UUID") @PathVariable String id,
            @RequestBody String news) {
        log.info("PUT /api/signals/{}/news — headline: {}", id, news);
        signalStore.updateNews(id, news);
        return ResponseEntity.ok().build();
    }

    @Operation(summary = "Get live symbol state",
            description = "Returns the in-memory SymbolState (candles, indicators, VWAP) for the given ticker.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "State returned"),
            @ApiResponse(responseCode = "404", description = "Symbol not yet tracked")
    })
    @GetMapping("/state/{symbol}")
    public ResponseEntity<SymbolState> state(
            @Parameter(description = "Ticker symbol") @PathVariable String symbol) {
        log.debug("GET /api/signals/state/{}", symbol);
        return aggregateMinuteBarHandler.getState(symbol)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
}