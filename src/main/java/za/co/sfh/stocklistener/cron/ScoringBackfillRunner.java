package za.co.sfh.stocklistener.cron;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import za.co.sfh.stocklistener.scoring.InstitutionalScoringService;

import java.time.LocalDate;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "scoring.backfill.enabled", havingValue = "true")
public class ScoringBackfillRunner implements ApplicationRunner {

    private final InstitutionalScoringService scoringService;

    @Value("${scoring.backfill.from}")
    private LocalDate from;

    @Value("${scoring.backfill.to}")
    private LocalDate to;

    @Override
    public void run(ApplicationArguments args) {
        log.info("Backfill triggered: from={} to={}", from, to);
        scoringService.backfill(from, to);
    }
}