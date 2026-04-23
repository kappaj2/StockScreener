package za.co.sfh.stocklistener.cron;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

@Slf4j
@Component
@RequiredArgsConstructor
public class DailyBarSummaryJob {

    private final JdbcTemplate jdbcTemplate;

    @Scheduled(cron = "${cron.daily-summary.run}", zone = "${massive.cron.zone}")
    public void summarisePreviousDay() {
        summarise(LocalDate.now().minusDays(1));
    }

    public void summarise(LocalDate tradeDate) {
        LocalDate nextDay = tradeDate.plusDays(1);
        log.info("Building daily_bar_summary for {}", tradeDate);

        // language=MariaDB
        String sql = """
                INSERT INTO daily_bar_summary
                    (symbol, trade_date, first_ts, last_ts,
                     open_price, high_price, low_price, close_price,
                     volume, bar_count, created_at)
                SELECT
                    mb.symbol,
                    DATE(mb.bar_start)                                                    AS trade_date,
                    MIN(mb.bar_start)                                                     AS first_ts,
                    MAX(mb.bar_start)                                                     AS last_ts,
                    (SELECT open_price FROM minute_bar m2
                     WHERE m2.symbol = mb.symbol AND DATE(m2.bar_start) = DATE(mb.bar_start)
                     ORDER BY m2.bar_start ASC LIMIT 1)                                  AS open_price,
                    MAX(mb.high_price)                                                    AS high_price,
                    MIN(mb.low_price)                                                     AS low_price,
                    (SELECT close_price FROM minute_bar m3
                     WHERE m3.symbol = mb.symbol AND DATE(m3.bar_start) = DATE(mb.bar_start)
                     ORDER BY m3.bar_start DESC LIMIT 1)                                 AS close_price,
                    SUM(mb.volume)                                                        AS volume,
                    COUNT(*)                                                              AS bar_count,
                    NOW(3)                                                                AS created_at
                FROM minute_bar mb
                WHERE mb.bar_start >= ? AND mb.bar_start < ?
                GROUP BY mb.symbol, DATE(mb.bar_start)
                ON DUPLICATE KEY UPDATE
                    first_ts    = VALUES(first_ts),
                    last_ts     = VALUES(last_ts),
                    open_price  = VALUES(open_price),
                    high_price  = VALUES(high_price),
                    low_price   = VALUES(low_price),
                    close_price = VALUES(close_price),
                    volume      = VALUES(volume),
                    bar_count   = VALUES(bar_count),
                    created_at  = VALUES(created_at)
                """;

        try {
            int rows = jdbcTemplate.update(sql, tradeDate.atStartOfDay(), nextDay.atStartOfDay());
            log.info("daily_bar_summary populated {} rows for {}", rows, tradeDate);
        } catch (Exception e) {
            log.error("Failed to build daily_bar_summary for {}: {}", tradeDate, e.getMessage(), e);
        }
    }
}
