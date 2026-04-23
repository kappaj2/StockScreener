package za.co.sfh.stocklistener.controller;

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
public class FireTraderController {

    private final FireTraderPatternScanner fireTraderPatternScanner;

    @GetMapping("/scan")
    public ResponseEntity<?> scanForFireTraders() {
        log.info("Received request to scan for possible fire traders");

        fireTraderPatternScanner.scanForPossibleFireTraders();

        return ResponseEntity.ok("Scan completed");
    }
}
