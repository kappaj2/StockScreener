package za.co.sfh.stocklistener.processor.scanners.scalping;

import za.co.sfh.stocklistener.payloads.FiveMinuteBar;
import za.co.sfh.stocklistener.processor.states.FiveMinuteState;
import za.co.sfh.stocklistener.signals.BreakoutSignal;

import java.util.Optional;

/**
 * Strategy interface for scalping pattern scanners that operate on 5-minute bars.
 *
 * <p>Implementations are auto-collected by
 * {@link za.co.sfh.stocklistener.processor.aggregators.FiveMinuteBarAggregator} via Spring
 * injection and run after every completed 5-minute bar.
 *
 * <p>Sub-packages:
 * <ul>
 *   <li>{@code rumers} — news/rumour-driven momentum setups</li>
 *   <li>{@code smb} — small-account / small-cap scalping setups</li>
 * </ul>
 *
 * <p>Implementations must be annotated {@code @Component} and must never hold per-symbol state —
 * all state lives in {@link FiveMinuteState}.
 */
public interface ScalpingPatternScanner {

    /**
     * Examines the just-completed 5-minute bar and its accumulated state.
     *
     * @return a populated signal if the pattern is detected, or empty if not
     */
    Optional<BreakoutSignal> scan(FiveMinuteBar bar, FiveMinuteState state);
}
