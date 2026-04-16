package za.co.sfh.stocklistener.processor.indicators;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import za.co.sfh.stocklistener.payloads.AggregateMinuteBar;

import java.util.List;

/**
 * Moving Average Convergence Divergence (MACD) indicator.
 *
 * <p>MACD measures the relationship between two Exponential Moving Averages (EMAs) of the closing
 * price. Classic parameters are fast=12, slow=26, signal=9. All three are configurable via
 * {@code application.yaml}.
 *
 * <h3>Derived signals</h3>
 * <ul>
 *   <li><b>Bullish crossover</b>: the MACD line crosses above the Signal line on the current bar
 *       (previous bar had MACD ≤ Signal). Classic buy trigger.</li>
 *   <li><b>Above zero</b>: MACD line is positive, meaning the short EMA has overtaken the long EMA —
 *       confirming a bullish trend.</li>
 *   <li><b>Expanding histogram</b>: the histogram (MACD − Signal) grew larger than the prior bar,
 *       indicating accelerating bullish momentum rather than fading.</li>
 * </ul>
 *
 * <p>Minimum bars required: {@code slowPeriod + signalPeriod − 1}.
 */
@Slf4j
@Component
public class MacdIndicator {

    /** Fast EMA period; override via {@code indicators.macd.fast}. */
    @Value("${indicators.macd.fast:12}")
    private int fastPeriod;

    /** Slow EMA period; override via {@code indicators.macd.slow}. */
    @Value("${indicators.macd.slow:26}")
    private int slowPeriod;

    /** Signal EMA period applied to the MACD line; override via {@code indicators.macd.signal}. */
    @Value("${indicators.macd.signal:9}")
    private int signalPeriod;

    // ── Result record ─────────────────────────────────────────────────────────

    /**
     * Computed MACD result.
     *
     * @param macdLine          Current value of the MACD line (fastEMA − slowEMA).
     * @param signalLine        Current value of the Signal line (EMA of MACD line).
     * @param histogram         MACD line − Signal line; positive values favour bulls.
     * @param bullishCrossover  {@code true} on the bar where MACD crosses above Signal from below.
     * @param aboveZero         {@code true} when the MACD line is positive (short EMA > long EMA).
     * @param expandingHistogram {@code true} when the histogram grew since the previous bar,
     *                           confirming momentum is increasing rather than waning.
     */
    public record MacdResult(double macdLine, double signalLine, double histogram,
                             boolean bullishCrossover, boolean aboveZero,
                             boolean expandingHistogram) {

        /** Sentinel returned when there are not enough bars to compute MACD. */
        public static MacdResult empty() {
            return new MacdResult(Double.NaN, Double.NaN, Double.NaN, false, false, false);
        }

        public boolean isValid() {
            return !Double.isNaN(macdLine);
        }
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Computes MACD from the full candle list.
     *
     * <p>EMAs are seeded with a simple moving average over the first {@code period} bars, then
     * smoothed forward with the standard EMA multiplier {@code 2 / (period + 1)}.
     *
     * @param candles ordered list of bars, oldest first (as returned by {@code SymbolState.getCandles()})
     * @return {@link MacdResult} with current MACD, Signal, histogram, and derived signals,
     *         or {@link MacdResult#empty()} if there are not enough bars
     */
    public MacdResult compute(List<AggregateMinuteBar> candles) {
        int minRequired = slowPeriod + signalPeriod - 1;
        if (candles.size() < minRequired) {
            log.debug("MACD: insufficient bars ({} < {})", candles.size(), minRequired);
            return MacdResult.empty();
        }

        double[] closes = candles.stream().mapToDouble(AggregateMinuteBar::close).toArray();

        // ── Build full EMA arrays for fast and slow periods ─────────────────────────
        double[] fastEmaArr = buildEmaArray(closes, fastPeriod);
        double[] slowEmaArr = buildEmaArray(closes, slowPeriod);

        // ── MACD line: valid from index (slowPeriod - 1) onwards ──────────────────
        int macdStart  = slowPeriod - 1;
        int macdLength = closes.length - macdStart;
        double[] macdArr = new double[macdLength];
        for (int i = 0; i < macdLength; i++) {
            macdArr[i] = fastEmaArr[macdStart + i] - slowEmaArr[macdStart + i];
        }

        if (macdArr.length < signalPeriod) {
            return MacdResult.empty();
        }

        // ── Signal line: EMA of the MACD line ─────────────────────────────────────
        double[] signalArr = buildEmaArray(macdArr, signalPeriod);

        double latestMacd   = macdArr[macdArr.length - 1];
        double latestSignal = signalArr[signalArr.length - 1];
        double histogram    = latestMacd - latestSignal;

        // ── Derive crossover and histogram-expansion signals ───────────────────────
        boolean bullishCrossover   = false;
        boolean expandingHistogram = false;

        if (macdArr.length >= 2 && signalArr.length >= 2) {
            double prevMacd      = macdArr[macdArr.length - 2];
            double prevSignal    = signalArr[signalArr.length - 2];
            double prevHistogram = prevMacd - prevSignal;

            bullishCrossover   = latestMacd > latestSignal && prevMacd <= prevSignal;
            expandingHistogram = histogram > 0 && histogram > prevHistogram;
        }

        log.debug("MACD computed: macd={:.4f}, signal={:.4f}, hist={:.4f}, crossover={}, aboveZero={}, expanding={}",
                latestMacd, latestSignal, histogram, bullishCrossover, latestMacd > 0, expandingHistogram);

        return new MacdResult(latestMacd, latestSignal, histogram,
                bullishCrossover, latestMacd > 0, expandingHistogram);
    }

    // ── Internal helpers ─────────────────────────────────────────────────────

    /**
     * Builds a full-length EMA array aligned with the input values.
     *
     * <p>Indices 0 through {@code period - 2} are left as {@code 0.0} (insufficient data).
     * Index {@code period - 1} is seeded with the SMA of the first {@code period} values.
     * All subsequent indices use the standard EMA multiplier.
     */
    private double[] buildEmaArray(double[] values, int period) {
        double[] ema = new double[values.length];
        if (values.length < period) return ema;

        double multiplier = 2.0 / (period + 1);

        // Seed: simple average of the first `period` values
        double sum = 0;
        for (int i = 0; i < period; i++) sum += values[i];
        ema[period - 1] = sum / period;

        // Smooth forward
        for (int i = period; i < values.length; i++) {
            ema[i] = values[i] * multiplier + ema[i - 1] * (1.0 - multiplier);
        }

        return ema;
    }
}
