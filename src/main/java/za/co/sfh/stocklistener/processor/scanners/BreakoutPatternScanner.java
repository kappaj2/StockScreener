package za.co.sfh.stocklistener.processor.scanners;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import za.co.sfh.stocklistener.payloads.AggregateMinuteBar;
import za.co.sfh.stocklistener.processor.PatternScanner;
import za.co.sfh.stocklistener.processor.states.SymbolState;
import za.co.sfh.stocklistener.signals.BreakoutSignal;
import za.co.sfh.stocklistener.signals.PatternType;

import java.util.Optional;
import java.util.UUID;

@Slf4j
@Component
public class BreakoutPatternScanner implements PatternScanner {

    @Value("${patterns.breakout.stop}")
    private double stopMultiplier;

    @Value("${patterns.breakout.target}")
    private double targetMultiplier;

    @Value("${patterns.breakout.storeSignal:false}")
    private boolean storeSignal;

    @Value("${patterns.breakout.min-two-bar-move:0.03}")
    private double minTwoBarMove;

    @Override
    public boolean shouldStore() {
        return storeSignal;
    }

    @Override
    public Optional<BreakoutSignal> scan(AggregateMinuteBar bar, SymbolState state) {
        if (state.getCandles().size() < 2) return Optional.empty();

        AggregateMinuteBar[] arr = state.getCandles().toArray(new AggregateMinuteBar[0]);
        AggregateMinuteBar previousBar = arr[arr.length - 2];

        if (bar.open() <= previousBar.close()) return Optional.empty();
        if (previousBar.close() <= previousBar.open()) return Optional.empty();

        double range = bar.high() - bar.low();
        log.debug("Breakout range check [symbol: {}; range: {}; close: {}]", bar.symbol(), range, bar.close());

        if (range <= 0) return Optional.empty();

        double previousRange = previousBar.high() - previousBar.low();
        if (previousRange <= 0) return Optional.empty();

        boolean confirmed = range > 2 * state.getAvgRange() &&
                bar.volume() > 2 * state.getAvgVolume() &&
                bar.close() > state.getAvgClose();

        log.debug("Breakout check [symbol: {}; range: {}; avgRange: {}; volume: {}; avgVolume: {}; close: {}; avgClose: {}; confirmed: {}]",
                bar.symbol(), range, state.getAvgRange(), bar.volume(), state.getAvgVolume(),
                bar.close(), state.getAvgClose(), confirmed);

        if (!confirmed) return Optional.empty();

        // Require the two-bar move (prev open → current close) to be at least minTwoBarMove %
        // to filter out tight ranging action that looks like a breakout but goes nowhere.
        double twoBarMove = (bar.close() - previousBar.open()) / previousBar.open();
        if (twoBarMove < minTwoBarMove) {
            log.debug("Breakout rejected — two-bar move {}% < minimum {}% [symbol: {}]",
                    String.format("%.2f", twoBarMove * 100), String.format("%.2f", minTwoBarMove * 100), bar.symbol());
            return Optional.empty();
        }

        log.info("Breakout above average [symbol: {}; close: {}; avgClose: {}; txTime: {}]",
                bar.symbol(), bar.close(), state.getAvgClose(), bar.endTimestampMs());

        log.debug("Previous bar [{}]", previousBar);
        log.debug("This bar [{}]", bar);

        return Optional.of(new BreakoutSignal(
                UUID.randomUUID().toString(),
                bar.symbol(),
                PatternType.BREAKOUT,
                bar.close(),
                bar.close() * stopMultiplier,
                bar.close() * targetMultiplier,
                100,
                "unknown",
                "rule-based breakout",
                bar.endTimestampMs().toInstant().toEpochMilli(),
                state.getPreMarketHigh(),
                state.getPreMarketLow(),
                null,
                false
        ));
    }
}
