# Institutional Buying Score — Build Instructions

## Context

This project already has a `daily_bar` table populated by a cron job that aggregates
minute bar data. The entity has these fields:
`symbol`, `trade_date`, `open_price`, `high_price`, `low_price`, `close_price`,
`volume`, `bar_count`, `first_ts`, `last_ts`, `created_at`.

We are adding an **institutional buying score** feature. It computes a set of
technical signals per stock per day (based on volume, price action, and bar
concentration) and writes a rolling 10-day score to a new `daily_bar_score` table.
A scheduled job runs this after the daily bar cron completes.

---

## Step 1 — Flyway Migration

Create a new Flyway migration file at:
`src/main/resources/db/migration/V{NEXT_VERSION}__create_daily_bar_score.sql`

Replace `{NEXT_VERSION}` with the next version number in the existing migration
sequence. Do not guess — check what the highest existing version number is in
`src/main/resources/db/migration/` and increment it.

```sql
CREATE TABLE daily_bar_score (
    symbol          VARCHAR(20)    NOT NULL,
    trade_date      DATE           NOT NULL,
    close_price     DECIMAL(18,6)  NOT NULL,
    price_change_pct DECIMAL(10,6) NOT NULL,
    volume          BIGINT         NOT NULL,
    vol_ratio       DECIMAL(10,4),          -- volume / 20d avg volume
    close_position  DECIMAL(10,6),          -- (close - low) / (high - low)
    vol_per_bar     DECIMAL(18,4),          -- volume / bar_count
    -- individual signal flags (0 or 1, quiet_accumulation can be 0 or 2)
    vol_spike           TINYINT NOT NULL DEFAULT 0,
    up_day              TINYINT NOT NULL DEFAULT 0,
    strong_close        TINYINT NOT NULL DEFAULT 0,
    tight_range         TINYINT NOT NULL DEFAULT 0,
    quiet_accumulation  TINYINT NOT NULL DEFAULT 0,
    vol_concentration   TINYINT NOT NULL DEFAULT 0,
    -- composite scores
    day_score           INT NOT NULL DEFAULT 0,
    rolling_10d_score   INT NOT NULL DEFAULT 0,
    computed_at         DATETIME NOT NULL,
    PRIMARY KEY (symbol, trade_date)
);

CREATE INDEX idx_dbs_trade_date       ON daily_bar_score (trade_date);
CREATE INDEX idx_dbs_rolling_score    ON daily_bar_score (rolling_10d_score DESC);
CREATE INDEX idx_dbs_symbol_date      ON daily_bar_score (symbol, trade_date DESC);
```

---

## Step 2 — JPA Entity

Create `DailyBarScore.java` in the same package as the existing `DailyBar` entity.
Use the same package conventions already in place.

```java
@Entity
@Table(name = "daily_bar_score")
@IdClass(DailyBarScoreId.class)
public class DailyBarScore {

    @Id
    @Column(nullable = false, length = 20)
    private String symbol;

    @Id
    @Column(name = "trade_date", nullable = false)
    private LocalDate tradeDate;

    @Column(name = "close_price", nullable = false, precision = 18, scale = 6)
    private BigDecimal closePrice;

    @Column(name = "price_change_pct", nullable = false, precision = 10, scale = 6)
    private BigDecimal priceChangePct;

    @Column(nullable = false)
    private Long volume;

    @Column(name = "vol_ratio", precision = 10, scale = 4)
    private BigDecimal volRatio;

    @Column(name = "close_position", precision = 10, scale = 6)
    private BigDecimal closePosition;

    @Column(name = "vol_per_bar", precision = 18, scale = 4)
    private BigDecimal volPerBar;

    @Column(name = "vol_spike", nullable = false)
    private Integer volSpike;

    @Column(name = "up_day", nullable = false)
    private Integer upDay;

    @Column(name = "strong_close", nullable = false)
    private Integer strongClose;

    @Column(name = "tight_range", nullable = false)
    private Integer tightRange;

    @Column(name = "quiet_accumulation", nullable = false)
    private Integer quietAccumulation;

    @Column(name = "vol_concentration", nullable = false)
    private Integer volConcentration;

    @Column(name = "day_score", nullable = false)
    private Integer dayScore;

    @Column(name = "rolling_10d_score", nullable = false)
    private Integer rolling10dScore;

    @Column(name = "computed_at", nullable = false, updatable = false)
    private Instant computedAt;

    // Generate getters, setters, and a no-arg constructor.
    // Also generate a builder or @Builder if the project uses Lombok.
}
```

Create the composite key class `DailyBarScoreId.java` in the same package:

```java
public class DailyBarScoreId implements Serializable {
    private String symbol;
    private LocalDate tradeDate;
    // equals, hashCode, no-arg constructor required
}
```

---

## Step 3 — Repository

Create `DailyBarScoreRepository.java` extending `JpaRepository<DailyBarScore, DailyBarScoreId>`.

Add these query methods:

```java
// All scores for a symbol, most recent first
List<DailyBarScore> findBySymbolOrderByTradeDateDesc(String symbol);

// Top N stocks by rolling score for a given date
@Query("SELECT d FROM DailyBarScore d WHERE d.tradeDate = :date ORDER BY d.rolling10dScore DESC")
List<DailyBarScore> findTopByDate(@Param("date") LocalDate date, Pageable pageable);

// Scores above a threshold on a given date
List<DailyBarScore> findByTradeDateAndRolling10dScoreGreaterThanEqualOrderByRolling10dScoreDesc(
        LocalDate tradeDate, int threshold);

// Delete all scores for a specific date (used before recompute)
void deleteByTradeDate(LocalDate tradeDate);
```

---

## Step 4 — Scoring Service

Create `InstitutionalScoringService.java` in the service layer.

This service computes scores using a **native SQL query** against `daily_bar` using
window functions, then upserts the results into `daily_bar_score`.

Use `@Transactional` on the compute method.

The service must:

1. Accept a `LocalDate targetDate` parameter — scores are computed for that date only.
2. Run the following native SQL query via `EntityManager` or `JdbcTemplate`
   (use whichever pattern is already in the project):

```sql
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
    FROM daily_bar
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
```

3. Map each result row to a `DailyBarScore` entity, set `computedAt = Instant.now()`.
4. Call `dailyBarScoreRepository.deleteByTradeDate(targetDate)` before saving,
   so a rerun is idempotent.
5. Batch save using `saveAll()`.
6. Log at INFO level: how many symbols were scored and how long it took.

Also add a convenience method:

```java
public List<DailyBarScore> getTopSignals(LocalDate date, int minScore, int limit) {
    // delegates to repository — returns stocks above minScore threshold, capped at limit
}
```

---

## Step 5 — Scheduler

Create `DailyBarScoringScheduler.java` in the scheduler/cron layer.

```java
@Component
public class DailyBarScoringScheduler {

    // Inject InstitutionalScoringService

    // Cron expression loaded from application properties key:
    // scoring.cron.daily-score
    // Default: "0 30 20 * * MON-FRI"  (8:30 PM weekdays — after market close processing)

    @Scheduled(cron = "${scoring.cron.daily-score:0 30 20 * * MON-FRI}")
    public void computeDailyScores() {
        // Compute scores for LocalDate.now()
        // Wrap in try/catch and log errors — do not let exceptions kill the scheduler thread
    }
}
```

Add to `application.properties` (or `application.yml` — match whichever the project uses):

```
scoring.cron.daily-score=0 30 20 * * MON-FRI
```

---

## Step 6 — REST Endpoint (optional but recommended)

Create `InstitutionalScoreController.java` if the project has a REST layer.

```
GET /api/scores/top?date={date}&minScore={minScore}&limit={limit}
    → returns top signals for a given date

GET /api/scores/{symbol}?from={from}&to={to}
    → returns score history for a symbol between two dates

POST /api/scores/recompute?date={date}
    → manually triggers recompute for a date (useful for backfill)
```

Follow the same response format, exception handling, and security patterns already
used in other controllers in the project.

---

## Step 7 — Backfill Utility

Add a method to `InstitutionalScoringService`:

```java
public void backfill(LocalDate from, LocalDate to) {
    // Iterate each weekday between from and to inclusive
    // Call computeForDate() for each
    // Log progress after each date
}
```

Expose it via a Spring Boot `ApplicationRunner` or `CommandLineRunner` that only
activates when a property `scoring.backfill.enabled=true` is set, with
`scoring.backfill.from` and `scoring.backfill.to` date properties.

---

## Constraints & Conventions

- Match all existing code style, package structure, and Lombok usage exactly.
- Do not introduce new dependencies unless unavoidable — check `pom.xml` first.
- All cron jobs must be guarded with try/catch to prevent thread death.
- The scoring query uses window functions — verify the MySQL version supports them
  (MySQL 8.0+ required). Check the Flyway baseline or existing migrations for hints.
- `deleteByTradeDate` must run in the same transaction as `saveAll` so a failed
  compute doesn't leave the table empty for that date.
- Do not add `@Transactional` to the scheduler method itself — only to the service
  method it calls.
