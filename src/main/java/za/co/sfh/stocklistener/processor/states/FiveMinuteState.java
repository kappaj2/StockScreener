package za.co.sfh.stocklistener.processor.states;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import za.co.sfh.stocklistener.payloads.FiveMinuteBar;

import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.List;

/**
 * Per-symbol rolling state for 5-minute candles.
 *
 * <p>Holds the last 78 completed 5-minute bars (one full regular session: 6.5 h × 12 bars/h).
 * Tracks a session VWAP, 9-period EMA of close, and running averages for range and volume.
 * All fields are reset on a new session date, consistent with {@link SymbolState} behaviour.
 *
 * <p>Previous-day high/low are set at the close of the regular session (16:00 ET) and are
 * never adjusted by pre-market bars — the aggregator filters those out before they reach here.
 */
@Slf4j
@Getter
public final class FiveMinuteState {

    // 6.5 h × 12 five-min bars = 78 bars per regular session
    private static final int MAX_BARS = 78;

    private static final int    EMA_PERIOD     = 9;
    private static final double EMA_MULTIPLIER = 2.0 / (EMA_PERIOD + 1);

    // ATR period — 14 bars is standard; matches the 1-min RSI period but on 5-min timeframe
    private static final int ATR_PERIOD = 14;

    private final ArrayDeque<FiveMinuteBar> bars = new ArrayDeque<>();

    private String symbol;
    private double avgRange;
    private double avgVolume;

    // Session VWAP (reset each day)
    private double cumulativePV;
    private double cumulativeVolume;
    private double vwap;

    // 9-period EMA of 5-min close
    private double ema9;
    private int    totalBars;
    private double emaSeedSum;

    // Previous full regular-session high/low — rolled at session boundary, never touched by pre-market
    private double prevDayHigh    = 0;
    private double prevDayLow     = 0;
    private double currentDayHigh = Double.MIN_VALUE;
    private double currentDayLow  = Double.MAX_VALUE;
    private LocalDate sessionDate = null;

    // Wilder's smoothed ATR on 5-min bars
    private double atr = 0;
    private int    atrBars = 0;
    private double atrSeedSum = 0;

    public void addBar(FiveMinuteBar bar) {
        if (symbol == null) {
            symbol = bar.symbol();
        }

        rollSessionIfNeeded(bar);
        updateVwap(bar);
        updateEma9(bar);
        updateAtr(bar);

        bars.addLast(bar);
        if (bars.size() > MAX_BARS) {
            bars.removeFirst();
        }

        updateAverages();
    }

    private void rollSessionIfNeeded(FiveMinuteBar bar) {
        LocalDate barDate = bar.endTime().toLocalDate();
        if (!barDate.equals(sessionDate)) {
            if (currentDayHigh != Double.MIN_VALUE && currentDayLow != Double.MAX_VALUE) {
                prevDayHigh = currentDayHigh;
                prevDayLow  = currentDayLow;
                log.debug("[{}] 5-min: rolled prev day range high={} low={}", symbol, prevDayHigh, prevDayLow);
            }
            currentDayHigh   = Double.MIN_VALUE;
            currentDayLow    = Double.MAX_VALUE;
            cumulativePV     = 0;
            cumulativeVolume = 0;
            sessionDate      = barDate;
        }
        if (bar.high() > currentDayHigh) currentDayHigh = bar.high();
        if (bar.low()  < currentDayLow)  currentDayLow  = bar.low();
    }

    private void updateVwap(FiveMinuteBar bar) {
        double typicalPrice = (bar.high() + bar.low() + bar.close()) / 3.0;
        cumulativePV     += typicalPrice * bar.volume();
        cumulativeVolume += bar.volume();
        vwap = cumulativeVolume > 0 ? cumulativePV / cumulativeVolume : 0;
    }

    private void updateEma9(FiveMinuteBar bar) {
        totalBars++;
        if (totalBars < EMA_PERIOD) {
            emaSeedSum += bar.close();
        } else if (totalBars == EMA_PERIOD) {
            emaSeedSum += bar.close();
            ema9 = emaSeedSum / EMA_PERIOD;
        } else {
            ema9 = bar.close() * EMA_MULTIPLIER + ema9 * (1 - EMA_MULTIPLIER);
        }
    }

    private void updateAtr(FiveMinuteBar bar) {
        // True range for the first bar is simply the bar's range (no prior close available)
        FiveMinuteBar prev = bars.peekLast();
        double trueRange = prev == null
                ? bar.range()
                : Math.max(bar.high() - bar.low(),
                  Math.max(Math.abs(bar.high() - prev.close()),
                           Math.abs(bar.low()  - prev.close())));

        atrBars++;
        if (atrBars < ATR_PERIOD) {
            atrSeedSum += trueRange;
        } else if (atrBars == ATR_PERIOD) {
            atrSeedSum += trueRange;
            atr = atrSeedSum / ATR_PERIOD;
        } else {
            // Wilder's smoothing: ATR = (prevATR × (n-1) + TR) / n
            atr = (atr * (ATR_PERIOD - 1) + trueRange) / ATR_PERIOD;
        }
    }

    private void updateAverages() {
        if (bars.isEmpty()) return;
        double totalRange  = 0;
        double totalVolume = 0;
        for (FiveMinuteBar b : bars) {
            totalRange  += b.range();
            totalVolume += b.volume();
        }
        avgRange  = totalRange  / bars.size();
        avgVolume = totalVolume / bars.size();
    }

    public List<FiveMinuteBar> getBars() {
        return List.copyOf(bars);
    }

    /** Number of completed 5-minute bars in the rolling window. */
    public int size() {
        return bars.size();
    }
}
