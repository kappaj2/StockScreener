package za.co.sfh.stocklistener.signals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import za.co.sfh.stocklistener.ClearDatabase;
import za.co.sfh.stocklistener.DatabaseContainerTest;
import za.co.sfh.stocklistener.persistence.entities.SignalHistoryEntity;
import za.co.sfh.stocklistener.persistence.repositories.SignalHistoryRepository;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Confirms SignalStore is wired with a real {@link SignalHistoryRepository}
 * in the Spring context and that {@link SignalStore#add(BreakoutSignal)}
 * durably records the signal in {@code signal_history}.
 */
@SpringBootTest
@ActiveProfiles("test")
@ClearDatabase
class SignalStoreIntegrationTest implements DatabaseContainerTest {

    @Autowired
    private SignalStore signalStore;

    @Autowired
    private SignalHistoryRepository signalHistoryRepository;

    @Test
    @DisplayName("add() writes the signal to signal_history, surviving SignalStore being cleared")
    void addPersistsToHistoryAcrossAck() {
        BreakoutSignal signal = new BreakoutSignal(
                "integration-id-1", "AAPL", PatternType.BREAKOUT,
                10.0, 9.0, 11.0,
                90, "medium", "rule-based breakout", 1_000L,
                10.5, 9.5, null, false
        );

        signalStore.add(signal);
        signalStore.ackAll();

        assertThat(signalStore.size()).isZero();

        List<SignalHistoryEntity> history =
                signalHistoryRepository.findBySignalIdOrderByCreatedAtDesc("integration-id-1");

        assertThat(history).hasSize(1);
        assertThat(history.getFirst().getSymbol()).isEqualTo("AAPL");
    }
}
