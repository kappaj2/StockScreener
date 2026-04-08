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
}
