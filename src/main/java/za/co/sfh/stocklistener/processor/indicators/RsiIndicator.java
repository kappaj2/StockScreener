package za.co.sfh.stocklistener.processor.indicators;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import za.co.sfh.stocklistener.payloads.AggregateMinuteBar;

import java.util.List;

/**
 * Relative Strength Index (RSI) indicator using Wilder's smoothing method.
 *
 * <p>RSI measures momentum by comparing the magnitude of recent gains to recent losses,
 * normalising the result to a 0–100 scale. Values above 70 indicate overbought conditions
 * (potential exhaustion), values below 30 indicate oversold conditions (potential bounce),
 * and crossing above 50 from below confirms building buying pressure.
 *
 * <p>Implementation uses the full candle history available in {@code SymbolState} to ensure
 * Wilder's exponential smoothing is properly seeded before the first reported value.
 * A minimum of {@code period + 1} bars is required to return a valid result.
 */
@Slf4j
@Component
public class RsiIndicator {

    /** Standard RSI look-back period; override via {@code indicators.rsi.period}. */
    @Value("${indicators.rsi.period:14}")
    private int period;

    /** Overbought threshold; crossing above this level signals strong but potentially exhausted momentum. */
    @Value("${indicators.rsi.overbought:70}")
    private double overboughtThreshold;

    /** Oversold threshold; dropping below this level signals weakness with a potential bounce. */
    @Value("${indicators.rsi.oversold:30}")
    private double oversoldThreshold;

    // ── Result record ─────────────────────────────────────────────────────────

    /**
     * Computed RSI result.
     *
     * @param rsi             RSI value in the range [0, 100], or {@link Double#NaN} if insufficient data.
     * @param overbought      {@code true} when RSI ≥ overbought threshold (default 70).
     * @param oversold        {@code true} when RSI ≤ oversold threshold (default 30).
     * @param bullishMomentum {@code true} when RSI > 50, confirming net buying pressure.
     * @param risingAbove50   {@code true} when the current bar pushed RSI above 50 from below.
     */
    public record RsiResult(double rsi, boolean overbought, boolean oversold,
                            boolean bullishMomentum, boolean risingAbove50) {

        /** Sentinel returned when there are not enough bars to compute RSI. */
        public static RsiResult empty() {
            return new RsiResult(Double.NaN, false, false, false, false);
        }

        public boolean isValid() {
            return !Double.isNaN(rsi);
        }
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Computes RSI from the full candle list using Wilder's smoothing.
     *
     * <p>The entire available history is used to warm up the smoothed averages, so the
     * result improves as more bars accumulate — identical to how most charting platforms
     * calculate RSI when historical data is loaded.
     *
     * @param candles ordered list of bars, oldest first (as returned by {@code SymbolState.getCandles()})
     * @return {@link RsiResult} containing the RSI value and derived signals,
     *         or {@link RsiResult#empty()} if fewer than {@code period + 1} bars are available
     */
    public RsiResult compute(List<AggregateMinuteBar> candles) {
        if (candles.size() < period + 1) {
            log.debug("RSI: insufficient bars ({} < {})", candles.size(), period + 1);
            return RsiResult.empty();
        }

        double[] closes = candles.stream().mapToDouble(AggregateMinuteBar::close).toArray();

        // ── Step 1: seed average gain / loss with simple mean of first `period` changes ──
        double avgGain = 0;
        double avgLoss = 0;
        for (int i = 1; i <= period; i++) {
            double change = closes[i] - closes[i - 1];
            if (change > 0) avgGain += change;
            else avgLoss += Math.abs(change);
        }
        avgGain /= period;
        avgLoss /= period;

        // ── Step 2: Wilder's smoothing over remaining bars ─────────────────────────────
        double prevRsi = Double.NaN;
        double currentRsi = rsiFromAverages(avgGain, avgLoss);

        for (int i = period + 1; i < closes.length; i++) {
            double change = closes[i] - closes[i - 1];
            double gain   = Math.max(0, change);
            double loss   = Math.max(0, -change);

            avgGain = (avgGain * (period - 1) + gain) / period;
            avgLoss = (avgLoss * (period - 1) + loss) / period;

            prevRsi    = currentRsi;
            currentRsi = rsiFromAverages(avgGain, avgLoss);
        }

        boolean overbought      = currentRsi >= overboughtThreshold;
        boolean oversold        = currentRsi <= oversoldThreshold;
        boolean bullishMomentum = currentRsi > 50;
        boolean risingAbove50   = !Double.isNaN(prevRsi) && prevRsi <= 50 && currentRsi > 50;

        log.debug("RSI computed: rsi={:.2f}, overbought={}, oversold={}, bullish={}, crossedAbove50={}",
                currentRsi, overbought, oversold, bullishMomentum, risingAbove50);

        return new RsiResult(currentRsi, overbought, oversold, bullishMomentum, risingAbove50);
    }

    // ── Internal helpers ─────────────────────────────────────────────────────

    private double rsiFromAverages(double avgGain, double avgLoss) {
        if (avgLoss == 0) return 100.0;
        double rs = avgGain / avgLoss;
        return 100.0 - (100.0 / (1.0 + rs));
    }
}
