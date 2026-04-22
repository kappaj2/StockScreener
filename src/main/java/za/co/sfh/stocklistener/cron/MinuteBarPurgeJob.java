package za.co.sfh.stocklistener.cron;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import za.co.sfh.stocklistener.persistence.repositories.MinuteBarRepository;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Slf4j
@Component
@RequiredArgsConstructor
public class MinuteBarPurgeJob {

    private final MinuteBarRepository minuteBarRepository;

    @Value("${cron.purge.minute-bar-batch-size:1000}")
    private int batchSize;

    @Scheduled(cron = "${cron.purge.minute-bar}", zone = "${massive.cron.zone}")
    @Transactional
    public void purgeOldBars() {
        Instant cutoff = Instant.now().minus(90, ChronoUnit.DAYS);
        log.info("Purging minute_bar records older than {} in batches of {}", cutoff, batchSize);

        int total = 0;
        int deleted;
        do {
            deleted = deleteBatch(cutoff);
            total += deleted;
        } while (deleted == batchSize);

        log.info("Purged {} minute_bar records older than 3 months", total);
    }

    public int deleteBatch(Instant cutoff) {
        return minuteBarRepository.deleteBatchByCreatedAtBefore(cutoff, batchSize);
    }
}
