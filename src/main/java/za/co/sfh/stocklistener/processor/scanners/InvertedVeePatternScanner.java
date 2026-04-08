package za.co.sfh.stocklistener.processor.scanners;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import za.co.sfh.stocklistener.payloads.AggregateMinuteBar;
import za.co.sfh.stocklistener.processor.PatternScanner;
import za.co.sfh.stocklistener.processor.states.SymbolState;
import za.co.sfh.stocklistener.signals.BreakoutSignal;

import java.util.Optional;

/**
 * Scans for an inverted-V (sharp spike then rapid reversal) pattern.
 * Requires at least 20 candles of context to identify the peak and subsequent reversal.
 *
 * TODO: implement detection logic
 */
@Slf4j
@Component
public class InvertedVeePatternScanner implements PatternScanner {

    @Value("${patterns.invertedV.stop}")
    private double stopMultiplier;

    @Value("${patterns.invertedV.target}")
    private double targetMultiplier;


    @Override
    public Optional<BreakoutSignal> scan(AggregateMinuteBar bar, SymbolState state) {
        return Optional.empty();
    }
}
