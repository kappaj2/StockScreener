package za.co.sfh.stocklistener.cron;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import za.co.sfh.stocklistener.scoring.InstitutionalScoringService;

import java.time.LocalDate;

@Slf4j
@Component
@RequiredArgsConstructor
public class DailyBarScoringScheduler {

    private final InstitutionalScoringService scoringService;

    @Scheduled(cron = "${scoring.cron.daily-score:0 30 20 * * MON-FRI}", zone = "${massive.cron.zone}")
    public void computeDailyScores() {
        try {
            scoringService.computeForDate(LocalDate.now());
        } catch (Exception e) {
            log.error("Daily institutional score computation failed: {}", e.getMessage(), e);
        }
    }
}