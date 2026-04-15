package za.co.sfh.stocklistener.processor.scanners;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import za.co.sfh.stocklistener.payloads.AggregateMinuteBar;
import za.co.sfh.stocklistener.payloads.JohnWickType;
import za.co.sfh.stocklistener.processor.PatternScanner;
import za.co.sfh.stocklistener.processor.states.SymbolState;
import za.co.sfh.stocklistener.signals.BreakoutSignal;
import za.co.sfh.stocklistener.signals.PatternType;

import java.util.Optional;
import java.util.UUID;

/**
 * Detects the "Unsharpen" reversal pattern:
 *
 * <ol>
 *   <li>The candle before the John Wick is bearish (close &lt; open).</li>
 *   <li>The 2nd-to-last candle is a bullish John Wick (dominant lower wick, close near high).</li>
 *   <li>The current candle is green (close &gt; open) and closes <em>above</em> the John Wick candle's high.</li>
 * </ol>
 *
 * <p>Entry is the close of the confirming candle. Stop is placed below the John Wick low
 * (using the {@code patterns.unsharpen.stop} multiplier). Target uses
 * {@code patterns.unsharpen.target}.
 */
@Slf4j
@Component
public class UnsharpenPatternScanner implements PatternScanner {

    @Value("${patterns.unsharpen.stop}")
    private double stopMultiplier;

    @Value("${patterns.unsharpen.target}")
    private double targetMultiplier;

    @Value("${patterns.unsharpen.storeSignal:false}")
    private boolean storeSignal;

    @Override
    public boolean shouldStore() {
        return storeSignal;
    }

    @Override
    public Optional<BreakoutSignal> scan(AggregateMinuteBar bar, SymbolState state) {
        if (state.getCandles().size() < 3) return Optional.empty();

        AggregateMinuteBar[] arr = state.getCandles().toArray(new AggregateMinuteBar[0]);
        AggregateMinuteBar johnWickBar = arr[arr.length - 2];   // 2nd last
        AggregateMinuteBar priorBar    = arr[arr.length - 3];   // candle before John Wick

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

        double entry  = bar.close();
        double stop   = johnWickBar.low() * stopMultiplier;
        double target = entry * targetMultiplier;

        log.info("[{}] Unsharpen pattern confirmed — johnWickHigh={}, close={}, stop={}, target={}",
                bar.symbol(), johnWickBar.high(), entry, stop, target);

        return Optional.of(new BreakoutSignal(
                UUID.randomUUID().toString(),
                bar.symbol(),
                PatternType.UNSHARPEN_MASK,
                entry,
                stop,
                target,
                100,
                "unknown",
                String.format("Bearish bar → Bullish JohnWick (low=%.4f, high=%.4f) → Green breakout close=%.4f",
                        johnWickBar.low(), johnWickBar.high(), entry),
                System.currentTimeMillis(),
                state.getPreMarketHigh(),
                state.getPreMarketLow(),
                null
        ));
    }
}
