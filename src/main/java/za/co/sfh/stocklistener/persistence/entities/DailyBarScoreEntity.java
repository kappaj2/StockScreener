package za.co.sfh.stocklistener.persistence.entities;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

@Entity
@IdClass(DailyBarScoreId.class)
@Table(name = "daily_bar_score")
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DailyBarScoreEntity {

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
}