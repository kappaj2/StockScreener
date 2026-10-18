package za.co.sfh.stocklistener.processor.states;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import za.co.sfh.stocklistener.payloads.FiveMinuteBar;

import java.util.ArrayDeque;
import java.util.List;

/**
 * Per-symbol rolling state for 5-minute candles.
 *
 * <p>Holds the last 78 completed 5-minute bars (one full regular session: 6.5 h × 12 bars/h).
 * Tracks a session VWAP, 9-period EMA of close, and running averages for range and volume.
 * All fields are reset on a new session date, consistent with {@link SymbolState} behaviour.
 */
@Slf4j
@Getter
public final class FiveMinuteState {

    // 6.5 h × 12 five-min bars = 78 bars per regular session
    private static final int MAX_BARS = 78;

    private static final int    EMA_PERIOD     = 9;
    private static final double EMA_MULTIPLIER = 2.0 / (EMA_PERIOD + 1);

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

    public void addBar(FiveMinuteBar bar) {
        if (symbol == null) {
            symbol = bar.symbol();
        }

        updateVwap(bar);
        updateEma9(bar);

        bars.addLast(bar);
        if (bars.size() > MAX_BARS) {
            bars.removeFirst();
        }

        updateAverages();
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
