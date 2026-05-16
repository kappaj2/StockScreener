package za.co.sfh.stocklistener.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import za.co.sfh.stocklistener.processor.scanners.FireTraderPatternScanner;

@Slf4j
@RestController
@RequestMapping("/api/v1/firetrader")
@RequiredArgsConstructor
@Tag(name = "FireTrader", description = "FireTrader pattern scanner")
public class FireTraderController {

    private final FireTraderPatternScanner fireTraderPatternScanner;

    @Operation(summary = "Run FireTrader scan",
            description = "Triggers an immediate scan of all tracked symbols for the FireTrader pattern.")
    @ApiResponse(responseCode = "200", description = "Scan completed")
    @GetMapping("/scan")
    public ResponseEntity<?> scanForFireTraders() {
        log.info("Received request to scan for possible fire traders");
        fireTraderPatternScanner.scanForPossibleFireTraders();
        return ResponseEntity.ok("Scan completed");
    }
}