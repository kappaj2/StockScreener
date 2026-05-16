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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import za.co.sfh.stocklistener.persistence.entities.HighWatchStockEntity;
import za.co.sfh.stocklistener.processor.stockwatch.HighWatchStockService;

@Slf4j
@RestController
@RequestMapping("/api/v1/watch")
@RequiredArgsConstructor
@Tag(name = "Stock Watch", description = "Manage the high-watch stock list")
public class StockWatchController {

    private final HighWatchStockService stockWatchService;

    @Operation(summary = "Add a symbol to the watch list",
            description = "Registers a stock symbol for high-watch monitoring. Returns 409 if the symbol is already present.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Symbol added"),
            @ApiResponse(responseCode = "409", description = "Symbol already in watch list")
    })
    @PostMapping
    public ResponseEntity<HighWatchStockEntity> addStock(
            @Parameter(description = "Ticker symbol (case-insensitive)") @RequestParam String symbol,
            @Parameter(description = "Name of the source list (e.g. PreMarketGappers)") @RequestParam String sourceList) {
        log.info("POST /api/v1/watch — symbol={}, sourceList={}", symbol, sourceList);
        try {
            HighWatchStockEntity saved = stockWatchService.addStock(symbol, sourceList);
            return ResponseEntity.status(201).body(saved);
        } catch (IllegalStateException e) {
            return ResponseEntity.status(409).build();
        }
    }

    @Operation(summary = "Remove a symbol from the watch list")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Symbol removed"),
            @ApiResponse(responseCode = "404", description = "Symbol not found")
    })
    @DeleteMapping("/{symbol}")
    public ResponseEntity<Void> deleteStock(
            @Parameter(description = "Ticker symbol to remove") @PathVariable String symbol) {
        log.info("DELETE /api/v1/watch/{}", symbol);
        boolean deleted = stockWatchService.deleteStock(symbol);
        return deleted ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }
}