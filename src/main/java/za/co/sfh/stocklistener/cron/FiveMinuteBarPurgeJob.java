package za.co.sfh.stocklistener.cron;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class FiveMinuteBarPurgeJob {

    private final JdbcTemplate jdbcTemplate;

    private static final DateTimeFormatter PARTITION_FMT = DateTimeFormatter.ofPattern("yyyyMM");

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        addNextMonthPartition();
        purgeOldBars();
    }

    @Scheduled(cron = "${cron.purge.five-minute-bar}", zone = "${massive.cron.zone}")
    public void purgeOldBars() {
        YearMonth cutoff = YearMonth.now().minusMonths(3);
        log.info("Dropping five_minute_bar partitions older than {}", cutoff);

        List<String> partitions = jdbcTemplate.queryForList(
            "SELECT PARTITION_NAME FROM information_schema.PARTITIONS " +
            "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'five_minute_bar' AND PARTITION_NAME IS NOT NULL",
            String.class
        );

        for (String name : partitions) {
            if (!name.startsWith("p") || name.length() != 7) continue;
            try {
                YearMonth partitionMonth = YearMonth.parse(name.substring(1), PARTITION_FMT);
                if (partitionMonth.isBefore(cutoff)) {
                    log.info("Dropping five_minute_bar partition {}", name);
                    jdbcTemplate.execute("ALTER TABLE five_minute_bar DROP PARTITION " + name);
                    log.info("Dropped five_minute_bar partition {}", name);
                }
            } catch (Exception e) {
                log.warn("Could not drop five_minute_bar partition {}: {}", name, e.getMessage());
            }
        }
    }

    @Scheduled(cron = "${cron.partition.five-minute-add-next}", zone = "${massive.cron.zone}")
    public void addNextMonthPartition() {
        YearMonth nextMonth = YearMonth.now().plusMonths(1);
        String partitionName = "p" + nextMonth.format(PARTITION_FMT);
        LocalDate boundary = nextMonth.plusMonths(1).atDay(1);

        log.info("Adding five_minute_bar partition {} with boundary {}", partitionName, boundary);
        String sql = String.format(
            "ALTER TABLE five_minute_bar REORGANIZE PARTITION pmax INTO (" +
            "PARTITION %s VALUES LESS THAN (TO_DAYS('%s')), " +
            "PARTITION pmax VALUES LESS THAN MAXVALUE)",
            partitionName, boundary
        );
        try {
            jdbcTemplate.execute(sql);
            log.info("five_minute_bar partition {} added successfully", partitionName);
        } catch (Exception e) {
            log.warn("Could not add five_minute_bar partition {} (may already exist): {}", partitionName, e.getMessage());
        }
    }
}