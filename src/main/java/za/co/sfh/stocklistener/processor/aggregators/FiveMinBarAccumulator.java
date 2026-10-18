package za.co.sfh.stocklistener.processor.aggregators;

import za.co.sfh.stocklistener.payloads.AggregateMinuteBar;
import za.co.sfh.stocklistener.payloads.FiveMinuteBar;

import java.time.ZonedDateTime;

/**
 * Mutable accumulator that buffers up to 5 consecutive 1-minute bars for a single symbol.
 * Not thread-safe — callers must ensure per-symbol access is serialised
 * (guaranteed by the single-threaded message processing loop).
 */
final class FiveMinBarAccumulator {

    private String symbol;
    private double open;
    private double high  = Double.MIN_VALUE;
    private double low   = Double.MAX_VALUE;
    private double close;
    private long   totalVolume;
    private double cumulativePV;   // Σ(typicalPrice × volume) for VWAP
    private ZonedDateTime startTime;
    private ZonedDateTime endTime;
    private int barCount;

    void add(AggregateMinuteBar bar) {
        if (barCount == 0) {
            symbol    = bar.symbol();
            open      = bar.open();
            startTime = bar.startTimestampMs();
        }
        if (bar.high() > high) high = bar.high();
        if (bar.low()  < low)  low  = bar.low();
        close       = bar.close();
        endTime     = bar.endTimestampMs();
        totalVolume += bar.volume();
        double typicalPrice = (bar.high() + bar.low() + bar.close()) / 3.0;
        cumulativePV += typicalPrice * bar.volume();
        barCount++;
    }

    boolean isComplete() {
        return barCount == 5;
    }

    FiveMinuteBar build() {
        double vwap = totalVolume > 0 ? cumulativePV / totalVolume : 0;
        return new FiveMinuteBar(symbol, open, high, low, close, totalVolume, vwap, startTime, endTime);
    }

    void reset() {
        symbol       = null;
        open         = 0;
        high         = Double.MIN_VALUE;
        low          = Double.MAX_VALUE;
        close        = 0;
        totalVolume  = 0;
        cumulativePV = 0;
        startTime    = null;
        endTime      = null;
        barCount     = 0;
    }
}
