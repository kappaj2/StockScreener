package za.co.sfh.stocklistener.scoring;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import za.co.sfh.stocklistener.persistence.entities.DailyBarScoreEntity;
import za.co.sfh.stocklistener.persistence.repositories.DailyBarScoreRepository;

import java.sql.Date;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class InstitutionalScoringService {

    private final DailyBarScoreRepository repository;
    private final NamedParameterJdbcTemplate namedJdbc;

    // language=MariaDB
    private static final String SCORING_SQL = """
            WITH stats AS (
                SELECT
                    symbol,
                    trade_date,
                    close_price,
                    open_price,
                    volume,
                    bar_count,
                    (close_price - low_price) / NULLIF(high_price - low_price, 0)   AS close_position,
                    (close_price - open_price) / open_price                         AS price_change_pct,
                    (high_price - low_price) / open_price                           AS range_pct,
                    volume / bar_count                                               AS vol_per_bar,
                    AVG(volume) OVER (
                        PARTITION BY symbol
                        ORDER BY trade_date
                        ROWS BETWEEN 19 PRECEDING AND 1 PRECEDING
                    )                                                                AS avg_vol_20d,
                    AVG((high_price - low_price) / open_price) OVER (
                        PARTITION BY symbol
                        ORDER BY trade_date
                        ROWS BETWEEN 9 PRECEDING AND 1 PRECEDING
                    )                                                                AS avg_range_10d,
                    AVG(volume / bar_count) OVER (
                        PARTITION BY symbol
                        ORDER BY trade_date
                        ROWS BETWEEN 9 PRECEDING AND 1 PRECEDING
                    )                                                                AS avg_vol_per_bar_10d
                FROM daily_bar_summary
                WHERE trade_date <= :targetDate
            ),
            scored AS (
                SELECT *,
                    CASE WHEN volume > 1.5 * avg_vol_20d THEN 1 ELSE 0 END          AS vol_spike,
                    CASE WHEN price_change_pct > 0.002 THEN 1 ELSE 0 END            AS up_day,
                    CASE WHEN close_position > 0.65 THEN 1 ELSE 0 END               AS strong_close,
                    CASE WHEN range_pct < avg_range_10d THEN 1 ELSE 0 END           AS tight_range,
                    CASE WHEN volume > 1.5 * avg_vol_20d
                          AND ABS(price_change_pct) < 0.005 THEN 2 ELSE 0 END       AS quiet_accumulation,
                    CASE WHEN vol_per_bar > 1.8 * avg_vol_per_bar_10d THEN 1 ELSE 0 END AS vol_concentration
                FROM stats
                WHERE avg_vol_20d IS NOT NULL
            ),
            final_scored AS (
                SELECT
                    symbol,
                    trade_date,
                    close_price,
                    price_change_pct,
                    volume,
                    ROUND(volume / avg_vol_20d, 4)                                   AS vol_ratio,
                    close_position,
                    vol_per_bar,
                    vol_spike,
                    up_day,
                    strong_close,
                    tight_range,
                    quiet_accumulation,
                    vol_concentration,
                    (vol_spike + up_day + strong_close + tight_range
                        + quiet_accumulation + vol_concentration)                    AS day_score,
                    SUM(vol_spike + up_day + strong_close + tight_range
                          + quiet_accumulation + vol_concentration) OVER (
                        PARTITION BY symbol
                        ORDER BY trade_date
                        ROWS BETWEEN 9 PRECEDING AND CURRENT ROW
                    )                                                                AS rolling_10d_score
                FROM scored
            )
            SELECT *
            FROM final_scored
            WHERE trade_date = :targetDate
            ORDER BY rolling_10d_score DESC
            """;

    @Transactional
    public int computeForDate(LocalDate targetDate) {
        long start = System.currentTimeMillis();
        log.info("Computing institutional scores for {}", targetDate);

        var params = new MapSqlParameterSource("targetDate", Date.valueOf(targetDate));

        List<DailyBarScoreEntity> scores = namedJdbc.query(SCORING_SQL, params, (rs, rowNum) ->
                DailyBarScoreEntity.builder()
                        .symbol(rs.getString("symbol"))
                        .tradeDate(rs.getDate("trade_date").toLocalDate())
                        .closePrice(rs.getBigDecimal("close_price"))
                        .priceChangePct(rs.getBigDecimal("price_change_pct"))
                        .volume(rs.getLong("volume"))
                        .volRatio(rs.getBigDecimal("vol_ratio"))
                        .closePosition(rs.getBigDecimal("close_position"))
                        .volPerBar(rs.getBigDecimal("vol_per_bar"))
                        .volSpike(rs.getInt("vol_spike"))
                        .upDay(rs.getInt("up_day"))
                        .strongClose(rs.getInt("strong_close"))
                        .tightRange(rs.getInt("tight_range"))
                        .quietAccumulation(rs.getInt("quiet_accumulation"))
                        .volConcentration(rs.getInt("vol_concentration"))
                        .dayScore(rs.getInt("day_score"))
                        .rolling10dScore(rs.getInt("rolling_10d_score"))
                        .computedAt(Instant.now())
                        .build());

        repository.deleteByTradeDate(targetDate);
        repository.saveAll(scores);

        long elapsed = System.currentTimeMillis() - start;
        log.info("Scored {} symbols for {} in {}ms", scores.size(), targetDate, elapsed);
        return scores.size();
    }

    public List<DailyBarScoreEntity> getTopSignals(LocalDate date, int minScore, int limit) {
        return repository
                .findByTradeDateAndRolling10dScoreGreaterThanEqualOrderByRolling10dScoreDesc(date, minScore)
                .stream()
                .limit(limit)
                .toList();
    }

    public void backfill(LocalDate from, LocalDate to) {
        log.info("Starting institutional score backfill from {} to {}", from, to);
        LocalDate cursor = from;
        int daysProcessed = 0;
        while (!cursor.isAfter(to)) {
            if (cursor.getDayOfWeek() != DayOfWeek.SATURDAY && cursor.getDayOfWeek() != DayOfWeek.SUNDAY) {
                try {
                    int count = computeForDate(cursor);
                    log.info("Backfill {}: {} symbols scored", cursor, count);
                    daysProcessed++;
                } catch (Exception e) {
                    log.error("Backfill failed for {}: {}", cursor, e.getMessage(), e);
                }
            }
            cursor = cursor.plusDays(1);
        }
        log.info("Backfill complete — {} trading days processed", daysProcessed);
    }
}