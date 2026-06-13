package za.co.sfh.stocklistener.persistence.entities;

import jakarta.persistence.*;
import lombok.*;
import za.co.sfh.stocklistener.signals.BreakoutSignal;
import za.co.sfh.stocklistener.signals.PatternType;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Append-only history of every {@link BreakoutSignal} inserted into the
 * in-memory SignalStore. Recorded so signals are not lost on app restart or
 * when the in-memory pending queue is cleared.
 *
 * Not purged — no retention/cleanup job is required at this time.
 */
@Entity
@Table(name = "signal_history", indexes = {
        @Index(name = "idx_signal_history_symbol", columnList = "symbol"),
        @Index(name = "idx_signal_history_signal_id", columnList = "signal_id"),
        @Index(name = "idx_signal_history_created_at", columnList = "created_at")
})
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SignalHistoryEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "signal_id", nullable = false, length = 36)
    private String signalId;

    @Column(nullable = false, length = 20)
    private String symbol;

    @Column(nullable = false, length = 30)
    private String pattern;

    @Column(name = "entry_price", nullable = false, precision = 18, scale = 6)
    private BigDecimal entryPrice;

    @Column(name = "stop_price", nullable = false, precision = 18, scale = 6)
    private BigDecimal stopPrice;

    @Column(name = "target_price", nullable = false, precision = 18, scale = 6)
    private BigDecimal targetPrice;

    @Column(nullable = false)
    private Integer confidence;

    @Column(length = 255)
    private String risk;

    @Column(length = 1000)
    private String notes;

    @Column(name = "signal_timestamp", nullable = false)
    private Long signalTimestamp;

    @Column(name = "pre_market_high", precision = 18, scale = 6)
    private BigDecimal preMarketHigh;

    @Column(name = "pre_market_low", precision = 18, scale = 6)
    private BigDecimal preMarketLow;

    @Column(length = 1000)
    private String news;

    @Column(name = "high_watch", nullable = false)
    private boolean highWatch;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        createdAt = Instant.now();
    }

    /**
     * Maps a live {@link BreakoutSignal} to the persistence record stored
     * when it is added to the SignalStore.
     */
    public static SignalHistoryEntity from(BreakoutSignal signal) {
        return SignalHistoryEntity.builder()
                .signalId(signal.id())
                .symbol(signal.symbol())
                .pattern(signal.pattern().name())
                .entryPrice(BigDecimal.valueOf(signal.entry()))
                .stopPrice(BigDecimal.valueOf(signal.stop()))
                .targetPrice(BigDecimal.valueOf(signal.target()))
                .confidence(signal.confidence())
                .risk(signal.risk())
                .notes(signal.notes())
                .signalTimestamp(signal.timestamp())
                .preMarketHigh(BigDecimal.valueOf(signal.preMarketHigh()))
                .preMarketLow(BigDecimal.valueOf(signal.preMarketLow()))
                .news(signal.news())
                .highWatch(signal.highWatch())
                .build();
    }

    /**
     * Maps the persisted record back to a {@link PatternType}, mainly for
     * display/replay purposes.
     */
    public PatternType patternType() {
        return PatternType.valueOf(pattern);
    }
}
