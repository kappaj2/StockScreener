package za.co.sfh.stocklistener.persistence.entities;

import jakarta.persistence.*;
import lombok.*;
import za.co.sfh.stocklistener.payloads.FiveMinuteBar;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@IdClass(FiveMinuteBarId.class)
@Table(name = "five_minute_bar", indexes = {
        @Index(name = "idx_five_min_bar_start", columnList = "bar_start"),
        @Index(name = "idx_five_min_bar_created_at", columnList = "created_at")
})
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FiveMinuteBarEntity {

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

    @Column(precision = 18, scale = 6)
    private BigDecimal vwap;

    @Column(nullable = false, precision = 10, scale = 4)
    private BigDecimal rvol;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        createdAt = Instant.now();
    }

    public static FiveMinuteBarEntity from(FiveMinuteBar bar) {
        return FiveMinuteBarEntity.builder()
                .symbol(bar.symbol())
                .barStart(bar.startTime().toInstant())
                .barEnd(bar.endTime().toInstant())
                .openPrice(BigDecimal.valueOf(bar.open()))
                .highPrice(BigDecimal.valueOf(bar.high()))
                .lowPrice(BigDecimal.valueOf(bar.low()))
                .closePrice(BigDecimal.valueOf(bar.close()))
                .volume(bar.volume())
                .vwap(BigDecimal.valueOf(bar.vwap()))
                .rvol(BigDecimal.valueOf(bar.hasRvol() ? bar.rvol() : 1.0))
                .build();
    }
}