package za.co.sfh.stocklistener.persistence.entities;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "high_watch_stocks", indexes = {
        @Index(name = "from_indx", columnList = "valid_from"),
        @Index(name = "to_indx", columnList = "valid_to")
})
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HighWatchStockEntity {

    @Id
    @Column(nullable = false, length = 20)
    private String symbol;

    @Column(name = "valid_from", nullable = false)
    private LocalDateTime validFrom;

    @Column(name = "valid_to")
    private LocalDateTime validTo;

    @Column(name = "source_list", length = 255)
    private String sourceList;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void prePersist() {
        createdAt = LocalDateTime.now();
    }
}