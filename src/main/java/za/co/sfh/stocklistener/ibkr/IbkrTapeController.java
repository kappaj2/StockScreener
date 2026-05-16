package za.co.sfh.stocklistener.ibkr;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/tape")
@RequiredArgsConstructor
@Tag(name = "IBKR Tape", description = "Interactive Brokers real-time time-and-sales tape")
public class IbkrTapeController {

    private final IbkrTapeClient tapeClient;
    private final IbkrTickStore  tickStore;

    @Operation(summary = "Get rolling tape for a symbol",
            description = "Returns the rolling tick buffer for the symbol (oldest first). 404 if never subscribed or no ticks yet.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Tick buffer returned"),
            @ApiResponse(responseCode = "404", description = "Symbol not subscribed or no ticks")
    })
    @GetMapping("/{symbol}")
    public ResponseEntity<List<IbkrTickEvent>> getTape(
            @Parameter(description = "Ticker symbol") @PathVariable String symbol) {
        var ticks = tickStore.getTicks(symbol.toUpperCase());
        return ticks.isEmpty()
                ? ResponseEntity.notFound().build()
                : ResponseEntity.ok(ticks);
    }

    @Operation(summary = "Subscribe to real-time tape for a symbol",
            description = "Starts IBKR time-and-sales streaming. Returns 503 if not connected to TWS.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Subscription started"),
            @ApiResponse(responseCode = "503", description = "Not connected to IBKR TWS")
    })
    @PostMapping("/subscribe/{symbol}")
    public ResponseEntity<String> subscribe(
            @Parameter(description = "Ticker symbol") @PathVariable String symbol) {
        if (!tapeClient.isConnected()) {
            return ResponseEntity.status(503).body("Not connected to IBKR TWS — check ibkr.enabled and that TWS is running");
        }
        tapeClient.subscribe(symbol.toUpperCase());
        return ResponseEntity.ok("Subscribed to " + symbol.toUpperCase());
    }

    @Operation(summary = "Unsubscribe from tape and clear buffer")
    @ApiResponse(responseCode = "200", description = "Unsubscribed and buffer cleared")
    @DeleteMapping("/subscribe/{symbol}")
    public ResponseEntity<String> unsubscribe(
            @Parameter(description = "Ticker symbol") @PathVariable String symbol) {
        tapeClient.unsubscribe(symbol.toUpperCase());
        return ResponseEntity.ok("Unsubscribed from " + symbol.toUpperCase());
    }

    @Operation(summary = "List all subscribed symbols")
    @GetMapping("/symbols")
    public Set<String> subscribedSymbols() {
        return tapeClient.subscribedSymbols();
    }

    @Operation(summary = "IBKR connection status",
            description = "Returns connection state plus per-symbol tick counts.")
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