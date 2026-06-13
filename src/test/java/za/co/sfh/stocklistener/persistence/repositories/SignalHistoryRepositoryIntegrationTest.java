package za.co.sfh.stocklistener.persistence.repositories;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import za.co.sfh.stocklistener.ClearDatabase;
import za.co.sfh.stocklistener.DatabaseContainerTest;
import za.co.sfh.stocklistener.persistence.entities.SignalHistoryEntity;
import za.co.sfh.stocklistener.signals.BreakoutSignal;
import za.co.sfh.stocklistener.signals.PatternType;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test exercising the {@code V2026.06.13__create_signal_history.sql}
 * Flyway migration and {@link SignalHistoryRepository} against a real MariaDB
 * instance (Testcontainers).
 */
@SpringBootTest
@ActiveProfiles("test")
@ClearDatabase
class SignalHistoryRepositoryIntegrationTest implements DatabaseContainerTest {

    @Autowired
    private SignalHistoryRepository signalHistoryRepository;

    private SignalHistoryEntity entityFor(String signalId, String symbol, long timestamp, boolean highWatch) {
        BreakoutSignal signal = new BreakoutSignal(
                signalId, symbol, PatternType.BREAKOUT,
                10.0, 9.0, 11.0,
                90, "medium", "rule-based breakout", timestamp,
                10.5, 9.5, null, highWatch
        );
        return SignalHistoryEntity.from(signal);
    }

    @Test
    @DisplayName("save() persists a signal and assigns generated id + createdAt")
    void savePersistsSignal() {
        SignalHistoryEntity saved = signalHistoryRepository.save(entityFor("id-1", "AAPL", 1_000L, false));

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getCreatedAt()).isNotNull();

        SignalHistoryEntity reloaded = signalHistoryRepository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getSignalId()).isEqualTo("id-1");
        assertThat(reloaded.getSymbol()).isEqualTo("AAPL");
        assertThat(reloaded.getPattern()).isEqualTo("BREAKOUT");
        assertThat(reloaded.getEntryPrice()).isEqualByComparingTo("10.0");
        assertThat(reloaded.isHighWatch()).isFalse();
    }

    @Test
    @DisplayName("the same signal id can be recorded more than once (append-only history)")
    void sameSignalIdCanBeRecordedTwice() {
        signalHistoryRepository.save(entityFor("dup-id", "AAPL", 1_000L, false));
        signalHistoryRepository.save(entityFor("dup-id", "AAPL", 2_000L, true));

        List<SignalHistoryEntity> history = signalHistoryRepository.findBySignalIdOrderByCreatedAtDesc("dup-id");

        assertThat(history).hasSize(2);
    }

    @Test
    @DisplayName("findBySymbolOrderByCreatedAtDesc() returns only matching symbols")
    void findBySymbolReturnsOnlyMatching() {
        signalHistoryRepository.save(entityFor("id-1", "AAPL", 1_000L, false));
        signalHistoryRepository.save(entityFor("id-2", "MSFT", 2_000L, false));
        signalHistoryRepository.save(entityFor("id-3", "AAPL", 3_000L, false));

        List<SignalHistoryEntity> aapl = signalHistoryRepository.findBySymbolOrderByCreatedAtDesc("AAPL");

        assertThat(aapl).hasSize(2);
        assertThat(aapl).allSatisfy(e -> assertThat(e.getSymbol()).isEqualTo("AAPL"));
    }

    @Test
    @DisplayName("findAllByOrderByCreatedAtDesc() returns most recent first, respecting the page size")
    void findAllReturnsMostRecentFirst() {
        signalHistoryRepository.save(entityFor("id-1", "AAPL", 1_000L, false));
        signalHistoryRepository.save(entityFor("id-2", "MSFT", 2_000L, false));
        signalHistoryRepository.save(entityFor("id-3", "TSLA", 3_000L, false));

        List<SignalHistoryEntity> latestTwo =
                signalHistoryRepository.findAllByOrderByCreatedAtDesc(PageRequest.of(0, 2));

        assertThat(latestTwo).hasSize(2);
        assertThat(latestTwo.get(0).getSignalId()).isEqualTo("id-3");
        assertThat(latestTwo.get(1).getSignalId()).isEqualTo("id-2");
    }
}
