package za.co.sfh.stocklistener.processor.states;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import za.co.sfh.stocklistener.payloads.AggregateMinuteBar;

import java.util.ArrayDeque;
import java.util.List;

@Slf4j
@Getter
public final class SymbolState {

    private static final int MAX_CANDLES = 20;

    private final ArrayDeque<AggregateMinuteBar> candles = new ArrayDeque<>();

    private String symbol;
    private double avgRange;
    private double avgVolume;

    public void addBar(AggregateMinuteBar bar) {
        if (symbol == null) {
            symbol = bar.symbol();
            log.debug("SymbolState initialised for [{}]", symbol);
        }

        log.debug("[{}] Adding bar [size: {}]", symbol, candles.size());

        candles.addLast(bar);
        if (candles.size() > MAX_CANDLES) {
            candles.removeFirst();
        }

        updateAverages();
    }

    private void updateAverages() {
        if (candles.isEmpty()) return;

        double totalRange = 0;
        double totalVolume = 0;

        for (AggregateMinuteBar b : candles) {
            totalRange += (b.high() - b.low());
            totalVolume += b.volume();
        }

        avgRange = totalRange / candles.size();
        avgVolume = totalVolume / candles.size();
    }

    public boolean isBreakout(AggregateMinuteBar bar) {
        if (candles.size() < 2) return false;

        AggregateMinuteBar[] arr = candles.toArray(new AggregateMinuteBar[0]);
        AggregateMinuteBar previousBar = arr[arr.length - 2];

        if (bar.open() <= previousBar.close()) return false;
        if (previousBar.close() <= previousBar.open()) return false;

        double range = bar.high() - bar.low();
        log.debug("Range is [symbol: {}; bar high: {}; low: {}; range: {}]", symbol, bar.high(), bar.low(), range);

        if (range < 0.05 * bar.close()) return false;

        double previousRange = previousBar.high() - previousBar.low();
        if (previousRange < 0.05 * previousBar.close()) return false;

        var isBreakout = range > 2 * avgRange &&
                bar.volume() > 2 * avgVolume;

        log.debug("Breakout check [symbol: {}; high: {}; low: {}; range: {}; avgRange: {}; avgVolume: {}; isBeakout: {}]", symbol, bar.high(), bar.low(), range, avgRange, avgVolume, isBreakout);

        if (isBreakout) {
            log.debug("Previous bar [{}]", previousBar);
            log.debug("This bar [{}]", bar);
        }
        return isBreakout;
    }

    public List<AggregateMinuteBar> getCandles() {
        return List.copyOf(candles);
    }
}
