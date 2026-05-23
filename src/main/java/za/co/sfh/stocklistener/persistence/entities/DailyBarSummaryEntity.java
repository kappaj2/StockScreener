package za.co.sfh.stocklistener.persistence.entities;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

@Entity
@IdClass(DailyBarSummaryId.class)
@Table(name = "daily_bar_summary", indexes = {
        @Index(name = "idx_daily_summary_trade_date_symbol", columnList = "trade_date, symbol")
})
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DailyBarSummaryEntity {

    @Id
    @Column(nullable = false, length = 20)
    private String symbol;

    @Id
    @Column(name = "trade_date", nullable = false)
    private LocalDate tradeDate;

    @Column(name = "first_ts", nullable = false)
    private Instant firstTs;

    @Column(name = "last_ts", nullable = false)
    private Instant lastTs;

    @Column(name = "open_price", nullable = false, precision = 18, scale = 6)
    private BigDecimal openPrice;

    @Column(name = "high_price", nullable = false, precision = 18, scale = 6)
    private BigDecimal highPrice;

    @Column(name = "low_price", nullable = false, precision = 18, scale = 6)
    private BigDecimal lowPrice;

    @Column(name = "close_price", nullable = false, precision = 18, scale = 6)
    private BigDecimal closePrice;

    @Column(nullable = false)
    private Long volume;

    @Column(name = "bar_count", nullable = false)
    private Integer barCount;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        createdAt = Instant.now();
    }
}
