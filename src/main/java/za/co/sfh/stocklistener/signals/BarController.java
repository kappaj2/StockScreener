package za.co.sfh.stocklistener.signals;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
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

@Slf4j
@RestController
@RequestMapping("/api/bars")
@RequiredArgsConstructor
@Tag(name = "Bars", description = "Per-symbol 1-minute bar history")
public class BarController {

    private final AggregateMinuteBarHandler aggregateMinuteBarHandler;

    @Operation(summary = "Get minute bars for a symbol",
            description = "Returns the last N in-memory 1-minute aggregate bars for the given ticker. 404 if not yet tracked.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Bar list returned"),
            @ApiResponse(responseCode = "404", description = "Symbol not yet tracked")
    })
    @GetMapping("/{symbol}")
    public ResponseEntity<List<AggregateMinuteBar>> bars(
            @Parameter(description = "Ticker symbol (case-insensitive)") @PathVariable String symbol) {
        log.debug("GET /api/bars/{}", symbol);
        return aggregateMinuteBarHandler.getState(symbol.toUpperCase())
                .map(state -> ResponseEntity.ok(state.getCandles()))
                .orElse(ResponseEntity.notFound().build());
    }
}