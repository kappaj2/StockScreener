package za.co.sfh.stocklistener.persistence.entities;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import za.co.sfh.stocklistener.signals.BreakoutSignal;
import za.co.sfh.stocklistener.signals.PatternType;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("SignalHistoryEntity.from(BreakoutSignal)")
class SignalHistoryEntityTest {

    @Test
    @DisplayName("maps every field of the BreakoutSignal onto the entity")
    void mapsAllFields() {
        BreakoutSignal signal = new BreakoutSignal(
                "abc-123", "TSLA", PatternType.HIGH_TIGHT_FLAG,
                123.45, 110.0, 145.0,
                85, "low", "rule-based HTF", 1_700_000_000_000L,
                124.0, 121.5, "Some headline", true
        );

        SignalHistoryEntity entity = SignalHistoryEntity.from(signal);

        assertThat(entity.getId()).isNull(); // assigned by DB on insert
        assertThat(entity.getSignalId()).isEqualTo("abc-123");
        assertThat(entity.getSymbol()).isEqualTo("TSLA");
        assertThat(entity.getPattern()).isEqualTo("HIGH_TIGHT_FLAG");
        assertThat(entity.getEntryPrice()).isEqualByComparingTo("123.45");
        assertThat(entity.getStopPrice()).isEqualByComparingTo("110.0");
        assertThat(entity.getTargetPrice()).isEqualByComparingTo("145.0");
        assertThat(entity.getConfidence()).isEqualTo(85);
        assertThat(entity.getRisk()).isEqualTo("low");
        assertThat(entity.getNotes()).isEqualTo("rule-based HTF");
        assertThat(entity.getSignalTimestamp()).isEqualTo(1_700_000_000_000L);
        assertThat(entity.getPreMarketHigh()).isEqualByComparingTo("124.0");
        assertThat(entity.getPreMarketLow()).isEqualByComparingTo("121.5");
        assertThat(entity.getNews()).isEqualTo("Some headline");
        assertThat(entity.isHighWatch()).isTrue();
        assertThat(entity.getCreatedAt()).isNull(); // set by @PrePersist on save
    }

    @Test
    @DisplayName("patternType() round-trips the stored pattern name back to the enum")
    void patternTypeRoundTrips() {
        SignalHistoryEntity entity = SignalHistoryEntity.builder()
                .pattern(PatternType.GAP_AND_RUN.name())
                .build();

        assertThat(entity.patternType()).isEqualTo(PatternType.GAP_AND_RUN);
    }
}
