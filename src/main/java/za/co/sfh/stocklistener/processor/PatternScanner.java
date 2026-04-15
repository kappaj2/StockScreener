package za.co.sfh.stocklistener.processor;

import za.co.sfh.stocklistener.payloads.AggregateMinuteBar;
import za.co.sfh.stocklistener.processor.states.SymbolState;
import za.co.sfh.stocklistener.signals.BreakoutSignal;

import java.util.Optional;

public interface PatternScanner {

    /**
     * Examines the current bar and its accumulated state.
     * Returns a populated signal if the pattern is confirmed, or empty if not.
     */
    Optional<BreakoutSignal> scan(AggregateMinuteBar bar, SymbolState state);

    /**
     * Controls whether a matched signal is persisted to {@link za.co.sfh.stocklistener.signals.SignalStore}.
     * Each implementation reads its own {@code patterns.<name>.storeSignal} config property.
     * Defaults to {@code false} so new scanners are opt-in.
     */
    default boolean shouldStore() {
        return false;
    }
}
