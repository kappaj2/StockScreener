package za.co.sfh.stocklistener.processor.scanners;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import za.co.sfh.stocklistener.payloads.AggregateMinuteBar;
import za.co.sfh.stocklistener.payloads.JohnWickType;
import za.co.sfh.stocklistener.processor.PatternScanner;
import za.co.sfh.stocklistener.processor.states.SymbolState;
import za.co.sfh.stocklistener.signals.BreakoutSignal;

import java.util.Optional;

/**
 * Detects the "Unsharpen" reversal pattern:
 *
 * <ol>
 *   <li>The candle before the John Wick is bearish (close &lt; open).</li>
 *   <li>The 2nd-to-last candle is a bullish John Wick (dominant lower wick, close near high).</li>
 *   <li>The current candle is green (close &gt; open) and closes <em>above</em> the John Wick candle's high.</li>
 * </ol>
 */
@Slf4j
@Component
public class UnsharpenPatternScanner implements PatternScanner {

    @Override
    public Optional<BreakoutSignal> scan(AggregateMinuteBar bar, SymbolState state) {
        if (state.getCandles().size() < 3) return Optional.empty();

        AggregateMinuteBar[] arr = state.getCandles().toArray(new AggregateMinuteBar[0]);
        AggregateMinuteBar johnWickBar  = arr[arr.length - 2];   // 2nd last
        AggregateMinuteBar priorBar     = arr[arr.length - 3];   // candle before John Wick

        // 1. Prior candle must be bearish
        if (priorBar.close() >= priorBar.open()) {
            log.debug("[{}] Unsharpen rejected — prior candle not bearish", bar.symbol());
            return Optional.empty();
        }

        // 2. 2nd-last candle must be a bullish John Wick
        if (johnWickBar.johnWickType() != JohnWickType.BULLISH) {
            log.debug("[{}] Unsharpen rejected — 2nd-last candle is not a bullish John Wick (type={})",
                    bar.symbol(), johnWickBar.johnWickType());
            return Optional.empty();
        }

        // 3. Current candle must be green and close above the John Wick high
        if (bar.close() <= bar.open()) {
            log.debug("[{}] Unsharpen rejected — current candle is not green", bar.symbol());
            return Optional.empty();
        }
        if (bar.close() <= johnWickBar.high()) {
            log.debug("[{}] Unsharpen rejected — close ({}) does not exceed John Wick high ({})",
                    bar.symbol(), bar.close(), johnWickBar.high());
            return Optional.empty();
        }

        log.info("[{}] Unsharpen pattern confirmed — johnWickHigh={}, close={}",
                bar.symbol(), johnWickBar.high(), bar.close());

        // Pattern confirmed but signal emission is disabled pending further refinement
        return Optional.empty();
    }
}
