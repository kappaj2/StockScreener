package za.co.sfh.stocklistener.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import za.co.sfh.stocklistener.persistence.entities.DailyBarScoreEntity;
import za.co.sfh.stocklistener.persistence.repositories.DailyBarScoreRepository;
import za.co.sfh.stocklistener.scoring.InstitutionalScoringService;

import java.time.LocalDate;
import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/scores")
@RequiredArgsConstructor
@Tag(name = "Institutional Scores", description = "Daily institutional buying score signals")
public class InstitutionalScoreController {

    private final InstitutionalScoringService scoringService;
    private final DailyBarScoreRepository repository;

    @Operation(summary = "Top signals for a date",
            description = "Returns stocks above the minimum rolling-10d score, ordered by score descending.")
    @GetMapping("/top")
    public List<DailyBarScoreEntity> topSignals(
            @Parameter(description = "Trade date (ISO: yyyy-MM-dd), defaults to today")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @Parameter(description = "Minimum rolling_10d_score (inclusive)")
            @RequestParam(defaultValue = "5") int minScore,
            @Parameter(description = "Maximum number of results")
            @RequestParam(defaultValue = "50") int limit) {
        LocalDate target = date != null ? date : LocalDate.now();
        log.debug("GET /api/scores/top date={} minScore={} limit={}", target, minScore, limit);
        return scoringService.getTopSignals(target, minScore, limit);
    }

    @Operation(summary = "Score history for a symbol")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "History returned"),
            @ApiResponse(responseCode = "204", description = "No scores found for symbol")
    })
    @GetMapping("/{symbol}")
    public ResponseEntity<List<DailyBarScoreEntity>> symbolHistory(
            @Parameter(description = "Ticker symbol") @PathVariable String symbol,
            @Parameter(description = "From date (ISO: yyyy-MM-dd), defaults to 30 days ago")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @Parameter(description = "To date (ISO: yyyy-MM-dd), defaults to today")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        LocalDate toDate = to != null ? to : LocalDate.now();
        LocalDate fromDate = from != null ? from : toDate.minusDays(30);
        log.debug("GET /api/scores/{} from={} to={}", symbol, fromDate, toDate);
        List<DailyBarScoreEntity> scores = repository.findBySymbolAndTradeDateBetweenOrderByTradeDateDesc(
                symbol.toUpperCase(), fromDate, toDate);
        return scores.isEmpty() ? ResponseEntity.noContent().build() : ResponseEntity.ok(scores);
    }

    @Operation(summary = "Manually recompute scores for a date",
            description = "Deletes existing scores for the date and recomputes. Useful for backfill or reruns.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Recompute triggered, returns symbol count"),
            @ApiResponse(responseCode = "500", description = "Computation failed")
    })
    @PostMapping("/recompute")
    public ResponseEntity<String> recompute(
            @Parameter(description = "Trade date (ISO: yyyy-MM-dd), defaults to today")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        LocalDate target = date != null ? date : LocalDate.now();
        log.info("POST /api/scores/recompute date={}", target);
        try {
            int count = scoringService.computeForDate(target);
            return ResponseEntity.ok("Scored %d symbols for %s".formatted(count, target));
        } catch (Exception e) {
            log.error("Recompute failed for {}: {}", target, e.getMessage(), e);
            return ResponseEntity.internalServerError().body("Recompute failed: " + e.getMessage());
        }
    }
}