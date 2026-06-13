package za.co.sfh.stocklistener.signals;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import za.co.sfh.stocklistener.persistence.entities.SignalHistoryEntity;
import za.co.sfh.stocklistener.persistence.repositories.SignalHistoryRepository;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link SignalStore}.
 *
 * <p>Verifies both the in-memory pending-queue behaviour and that every
 * call to {@link SignalStore#add(BreakoutSignal)} is mirrored to the
 * append-only {@code signal_history} table via {@link SignalHistoryRepository}.
 */
@DisplayName("SignalStore")
@ExtendWith(MockitoExtension.class)
class SignalStoreTest {

    @Mock
    private SignalHistoryRepository signalHistoryRepository;

    private SignalStore signalStore;

    @BeforeEach
    void setUp() {
        signalStore = new SignalStore(signalHistoryRepository);
    }

    private BreakoutSignal signal(String id, String symbol, long timestamp) {
        return new BreakoutSignal(
                id, symbol, PatternType.BREAKOUT,
                10.0, 9.0, 11.0,
                90, "medium", "rule-based breakout", timestamp,
                10.5, 9.5, null, false
        );
    }

    @Test
    @DisplayName("add() stores the signal in the pending queue")
    void addStoresSignalInPendingQueue() {
        BreakoutSignal signal = signal("id-1", "AAPL", 1_000L);

        signalStore.add(signal);

        assertThat(signalStore.size()).isEqualTo(1);
        assertThat(signalStore.peekAll()).containsExactly(signal);
    }

    @Test
    @DisplayName("add() persists a SignalHistoryEntity mirroring the signal")
    void addPersistsHistoryRecord() {
        BreakoutSignal signal = signal("id-1", "AAPL", 1_000L);

        signalStore.add(signal);

        ArgumentCaptor<SignalHistoryEntity> captor = ArgumentCaptor.forClass(SignalHistoryEntity.class);
        verify(signalHistoryRepository).save(captor.capture());

        SignalHistoryEntity saved = captor.getValue();
        assertThat(saved.getSignalId()).isEqualTo("id-1");
        assertThat(saved.getSymbol()).isEqualTo("AAPL");
        assertThat(saved.getPattern()).isEqualTo("BREAKOUT");
        assertThat(saved.getEntryPrice()).isEqualByComparingTo("10.0");
        assertThat(saved.getStopPrice()).isEqualByComparingTo("9.0");
        assertThat(saved.getTargetPrice()).isEqualByComparingTo("11.0");
        assertThat(saved.getConfidence()).isEqualTo(90);
        assertThat(saved.getSignalTimestamp()).isEqualTo(1_000L);
        assertThat(saved.isHighWatch()).isFalse();
    }

    @Test
    @DisplayName("add() still updates the pending queue even if history persistence fails")
    void addToleratesHistoryPersistenceFailure() {
        when(signalHistoryRepository.save(any())).thenThrow(new RuntimeException("db down"));
        BreakoutSignal signal = signal("id-1", "AAPL", 1_000L);

        signalStore.add(signal);

        assertThat(signalStore.size()).isEqualTo(1);
        assertThat(signalStore.peekAll()).containsExactly(signal);
    }

    @Test
    @DisplayName("peekAll() returns signals sorted by timestamp")
    void peekAllSortsByTimestamp() {
        BreakoutSignal later = signal("id-2", "AAPL", 2_000L);
        BreakoutSignal earlier = signal("id-1", "MSFT", 1_000L);

        signalStore.add(later);
        signalStore.add(earlier);

        assertThat(signalStore.peekAll()).containsExactly(earlier, later);
    }

    @Test
    @DisplayName("ackAll() clears the pending queue")
    void ackAllClearsQueue() {
        signalStore.add(signal("id-1", "AAPL", 1_000L));

        signalStore.ackAll();

        assertThat(signalStore.size()).isZero();
        assertThat(signalStore.peekAll()).isEmpty();
    }

    @Test
    @DisplayName("remove() removes a single signal by id")
    void removeRemovesSingleSignal() {
        signalStore.add(signal("id-1", "AAPL", 1_000L));
        signalStore.add(signal("id-2", "MSFT", 2_000L));

        signalStore.remove("id-1");

        List<BreakoutSignal> remaining = signalStore.peekAll();
        assertThat(remaining).hasSize(1);
        assertThat(remaining.getFirst().id()).isEqualTo("id-2");
    }

    @Test
    @DisplayName("updateNews() attaches a headline to the matching pending signal")
    void updateNewsAttachesHeadline() {
        signalStore.add(signal("id-1", "AAPL", 1_000L));

        signalStore.updateNews("id-1", "Big news!");

        assertThat(signalStore.peekAll().getFirst().news()).isEqualTo("Big news!");
    }
}
