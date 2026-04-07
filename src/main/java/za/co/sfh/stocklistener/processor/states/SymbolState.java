package za.co.sfh.stocklistener.processor.states;

import lombok.extern.slf4j.Slf4j;
import za.co.sfh.stocklistener.payloads.AggregateMinuteBar;

import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayDeque;

@Slf4j
public final class SymbolState {

    private static final int MAX_CANDLES = 20;

    private final ArrayDeque<AggregateMinuteBar> candles = new ArrayDeque<>();

    private String symbol;

    private double avgRange;
    private double avgVolume;

    private double premarketHigh = Double.MIN_VALUE;
    private double premarketLow = Double.MAX_VALUE;

    public void addBar(AggregateMinuteBar bar) {
        // Maintain rolling window

        if (symbol == null) {
            symbol = bar.symbol();
            log.debug("SymbolState initialised for [{}]", symbol);
        }

        log.debug("[{}] Adding bar [size: {}]", symbol, candles.size());

        candles.addLast(bar);
        if (candles.size() > MAX_CANDLES) {
            candles.removeFirst();
        }

        updatePremarket(bar);
        updateAverages();
    }

    private void updatePremarket(AggregateMinuteBar bar) {

        if (isPremarket(bar)) {
            premarketHigh = Math.max(premarketHigh, bar.high());
            premarketLow = Math.min(premarketLow, bar.low());
            log.debug("Is still pre-market: [symbol: {}; premarketHigh: {}; premarketLow: {}]", bar.symbol(), premarketHigh, premarketLow);
        }
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
        double range = bar.high() - bar.low();

        log.debug("[{}] Breakout check [high: {}; low: {}; range: {}; avgRange: {}; avgVolume: {}]", symbol, bar.high(), bar.low(), range, avgRange, avgVolume);
        return range > 2 * avgRange &&
                bar.volume() > 2 * avgVolume &&
                bar.close() > premarketHigh;
    }

    private boolean isPremarket(AggregateMinuteBar bar) {
        // TODO: convert to ET and check 04:00–09:30
        var txDateTime = Instant.ofEpochMilli(bar.startTimestampMs())
                .atZone(ZoneId.of("America/New_York"));
        log.debug("Transaction time: [{}]", txDateTime);

        return true;
    }
}