package za.co.sfh.stocklistener.persistence.entities;

import jakarta.persistence.*;
import lombok.*;
import za.co.sfh.stocklistener.journal.CycleContext;
import za.co.sfh.stocklistener.journal.TradeDirection;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "trade_journal", indexes = {
        @Index(name = "idx_trade_journal_date",   columnList = "trade_date"),
        @Index(name = "idx_trade_journal_ticker",  columnList = "ticker")
})
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TradeJournalEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "trade_date", nullable = false)
    private LocalDate tradeDate;

    @Column(nullable = false, length = 20)
    private String ticker;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private TradeDirection direction;

    @Column(name = "entry_price", nullable = false, precision = 18, scale = 6)
    private BigDecimal entryPrice;

    @Column(name = "stop_price", nullable = false, precision = 18, scale = 6)
    private BigDecimal stopPrice;

    @Column(name = "exit_price", precision = 18, scale = 6)
    private BigDecimal exitPrice;

    @Column(length = 20)
    private String timeframe;

    @Column(nullable = false, length = 50)
    private String setup;

    @Enumerated(EnumType.STRING)
    @Column(name = "cycle_context", length = 50)
    private CycleContext cycleContext;

    @Column(name = "followed_plan")
    private Boolean followedPlan;

    @Column(name = "screenshot_url", length = 500)
    private String screenshotUrl;

    @Column(name = "what_went_well", length = 1000)
    private String whatWentWell;

    @Column(name = "what_to_improve", length = 1000)
    private String whatToImprove;

    @Column(name = "signal_id", length = 36)
    private String signalId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void prePersist() {
        createdAt = Instant.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }
}