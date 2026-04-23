package za.co.sfh.stocklistener.persistence.entities;

import jakarta.persistence.*;
import lombok.*;
import za.co.sfh.stocklistener.payloads.AggregateMinuteBar;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@IdClass(MinuteBarId.class)
@Table(name = "minute_bar", indexes = {
        @Index(name = "idx_bar_start", columnList = "bar_start"),
        @Index(name = "idx_minute_bar_created_at", columnList = "created_at")
})
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MinuteBarEntity {

    @Id
    @Column(nullable = false, length = 20)
    private String symbol;

    @Id
    @Column(name = "bar_start", nullable = false)
    private Instant barStart;

    @Column(name = "bar_end", nullable = false)
    private Instant barEnd;

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

    @Column(name = "accumulated_volume", nullable = false)
    private Long accumulatedVolume;

    @Column(name = "official_open", precision = 18, scale = 6)
    private BigDecimal officialOpen;

    @Column(precision = 18, scale = 6)
    private BigDecimal vwap;

    @Column(name = "today_vwap", precision = 18, scale = 6)
    private BigDecimal todayVwap;

    @Column(name = "avg_trade_size")
    private Integer avgTradeSize;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        createdAt = Instant.now();
    }

    public static MinuteBarEntity from(AggregateMinuteBar bar) {
        return MinuteBarEntity.builder()
                .symbol(bar.symbol())
                .barStart(bar.startTimestampMs().toInstant())
                .barEnd(bar.endTimestampMs().toInstant())
                .openPrice(BigDecimal.valueOf(bar.open()))
                .highPrice(BigDecimal.valueOf(bar.high()))
                .lowPrice(BigDecimal.valueOf(bar.low()))
                .closePrice(BigDecimal.valueOf(bar.close()))
                .volume(bar.volume())
                .accumulatedVolume(bar.accumulatedVolume())
                .officialOpen(BigDecimal.valueOf(bar.officialOpen()))
                .vwap(BigDecimal.valueOf(bar.vwap()))
                .todayVwap(BigDecimal.valueOf(bar.todayVwap()))
                .avgTradeSize(bar.avgTradeSize())
                .build();
    }
}
