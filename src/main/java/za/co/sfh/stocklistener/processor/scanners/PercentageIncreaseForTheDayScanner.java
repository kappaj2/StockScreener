package za.co.sfh.stocklistener.processor.scanners;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import za.co.sfh.stocklistener.payloads.AggregateMinuteBar;
import za.co.sfh.stocklistener.processor.PatternScanner;
import za.co.sfh.stocklistener.processor.states.SymbolState;
import za.co.sfh.stocklistener.signals.BreakoutSignal;
import za.co.sfh.stocklistener.signals.PatternType;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Detects a strong sustained climb over a configurable look-back window.
 *
 * <p>Algorithm:
 * <ol>
 *   <li>Take the last {@code patterns.strongClimb.moveNumBars} candles from state.</li>
 *   <li>Find the bar with the lowest high (lh) and the bar with the highest high (hh).</li>
 *   <li>The hh bar must appear <em>after</em> the lh bar (the move is upward).</li>
 *   <li>If {@code (hh - lh) / lh_close * 100 >= movePercentage}, emit a STRONG_CLIMB signal.</li>
 * </ol>
 */
@Slf4j
@Component
public class PercentageIncreaseForTheDayScanner implements PatternScanner {

    @Value("${patterns.strongClimb.movePercentage}")
    private double movePercentage;

    @Value("${patterns.strongClimb.moveNumBars}")
    private int moveNumBars;

    @Value("${patterns.strongClimb.storeSignal:false}")
    private boolean storeSignal;

    @Override
    public boolean shouldStore() {
        return storeSignal;
    }

    @Override
    public Optional<BreakoutSignal> scan(AggregateMinuteBar bar, SymbolState state) {
        List<AggregateMinuteBar> candles = state.getCandles();
        if (candles.size() < moveNumBars) return Optional.empty();

        List<AggregateMinuteBar> window = candles.subList(candles.size() - moveNumBars, candles.size());

        int lhIndex = 0;
        int hhIndex = 0;
        for (int i = 1; i < window.size(); i++) {
            if (window.get(i).high() < window.get(lhIndex).high()) lhIndex = i;
            if (window.get(i).high() > window.get(hhIndex).high()) hhIndex = i;
        }

        // The climb must be forward in time: lh before hh
        if (hhIndex <= lhIndex) {
            log.debug("[{}] StrongClimb rejected — highest high does not follow lowest high (lhIdx={}, hhIdx={})",
                    bar.symbol(), lhIndex, hhIndex);
            return Optional.empty();
        }

        AggregateMinuteBar lhBar     = window.get(lhIndex);
        AggregateMinuteBar hhBar     = window.get(hhIndex);
        double             lh        = lhBar.high();
        double             hh        = hhBar.high();
        double             priceAtLh = lhBar.close();
        double             actualMove = (hh - lh) / priceAtLh * 100.0;

        log.debug("[{}] StrongClimb check — lh={}, hh={}, priceAtLh={}, move={:.2f}%, threshold={}%",
                bar.symbol(), lh, hh, priceAtLh, actualMove, movePercentage);

        if (actualMove < movePercentage) return Optional.empty();

        log.info("[{}] STRONG_CLIMB detected — lh={} (idx {}), hh={} (idx {}), move={:.2f}%",
                bar.symbol(), lh, lhIndex, hh, hhIndex, actualMove);

        return Optional.of(new BreakoutSignal(
                UUID.randomUUID().toString(),
                bar.symbol(),
                PatternType.STRONG_CLIMB,
                bar.close(),
                lhBar.low(),
                hh,
                100,
                "unknown",
                String.format("Strong climb %.2f%% over %d bars (lh=%.4f → hh=%.4f)",
                        actualMove, moveNumBars, lh, hh),
                System.currentTimeMillis(),
                state.getPreMarketHigh(),
                state.getPreMarketLow(),
                null
        ));
    }
}
