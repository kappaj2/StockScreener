package za.co.sfh.stocklistener.processor.indicators;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import za.co.sfh.stocklistener.payloads.AggregateMinuteBar;

import java.util.ArrayList;
import java.util.List;

/**
 * Rate of Change (ROC) indicator.
 *
 * <p>ROC measures the percentage price change between the current bar and a bar {@code N} periods
 * ago. A steeply rising ROC confirms strong, sustained upward acceleration. Comparing the current
 * ROC reading to a rolling average of recent ROC values detects whether the pace of the climb
 * is itself accelerating — a secondary signal of increasing conviction.
 *
 * <p>Formula: {@code ROC(t) = ((P_current - P_{t-N}) / P_{t-N}) × 100}
 *
 * <p>At least {@code period + 1} bars are required for a single ROC reading. An additional
 * {@code period} bars (total {@code 2 × period + 1}) are needed to compute the rolling average
 * used for acceleration detection.
 */
@Slf4j
@Component
public class RocIndicator {

    /** Look-back period in bars; override via {@code indicators.roc.period}. */
    @Value("${indicators.roc.period:10}")
    private int period;

    /**
     * Number of consecutive ROC readings used to build the rolling average for acceleration
     * detection; override via {@code indicators.roc.avg-window}.
     */
    @Value("${indicators.roc.avg-window:10}")
    private int avgWindow;

    /** A ROC that exceeds its rolling average by this factor is flagged as a surge. */
    @Value("${indicators.roc.surge-factor:1.5}")
    private double surgeFactor;

    // ── Result record ─────────────────────────────────────────────────────────

    /**
     * Computed ROC result.
     *
     * @param roc          Percentage change over the last {@code period} bars (e.g. +3.5 = +3.5 %).
     *                     {@link Double#NaN} when insufficient data.
     * @param avgRoc       Rolling mean of the last {@code avgWindow} ROC readings, or
     *                     {@link Double#NaN} when insufficient history.
     * @param accelerating {@code true} when the current ROC strictly exceeds {@code avgRoc},
     *                     indicating the pace of the move is picking up.
     * @param surge        {@code true} when {@code roc ≥ avgRoc × surgeFactor}, confirming an
     *                     above-average burst of momentum.
     */
    public record RocResult(double roc, double avgRoc, boolean accelerating, boolean surge) {

        /** Sentinel returned when there are not enough bars to compute ROC. */
        public static RocResult empty() {
            return new RocResult(Double.NaN, Double.NaN, false, false);
        }

        public boolean isValid() {
            return !Double.isNaN(roc);
        }
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Computes the Rate of Change for the most recent bar.
     *
     * @param candles ordered list of bars, oldest first (as returned by {@code SymbolState.getCandles()})
     * @return {@link RocResult} containing ROC, rolling average, and derived signals,
     *         or {@link RocResult#empty()} if fewer than {@code period + 1} bars are available
     */
    public RocResult compute(List<AggregateMinuteBar> candles) {
        if (candles.size() < period + 1) {
            log.debug("ROC: insufficient bars ({} < {})", candles.size(), period + 1);
            return RocResult.empty();
        }

        double current    = candles.get(candles.size() - 1).close();
        double nPeriodsAgo = candles.get(candles.size() - 1 - period).close();

        if (nPeriodsAgo == 0) {
            log.warn("ROC: price N periods ago is zero — cannot divide");
            return RocResult.empty();
        }

        double roc = ((current - nPeriodsAgo) / nPeriodsAgo) * 100.0;

        // ── Rolling average over the last `avgWindow` ROC readings ──────────────────
        double avgRoc    = computeRollingAvgRoc(candles);
        boolean isValid  = !Double.isNaN(avgRoc);
        boolean accelerating = isValid && roc > avgRoc;
        boolean surge        = isValid && avgRoc != 0 && roc >= avgRoc * surgeFactor;

        log.debug("ROC computed: roc={:.2f}%, avgRoc={:.2f}%, accelerating={}, surge={}",
                roc, avgRoc, accelerating, surge);

        return new RocResult(roc, avgRoc, accelerating, surge);
    }

    // ── Internal helpers ─────────────────────────────────────────────────────

    /**
     * Computes the arithmetic mean of the last {@code avgWindow} ROC readings.
     *
     * <p>Each ROC reading at index {@code i} is {@code ((close[i] - close[i - period]) / close[i - period]) × 100}.
     * Requires at least {@code period + avgWindow} bars in total.
     */
    private double computeRollingAvgRoc(List<AggregateMinuteBar> candles) {
        // We need `avgWindow` consecutive ROC readings ending at the last bar.
        // The earliest of those requires an index of (size - avgWindow - period).
        int minSize = period + avgWindow;
        if (candles.size() < minSize) {
            return Double.NaN;
        }

        List<Double> rocValues = new ArrayList<>(avgWindow);

        // Collect ROC for the last `avgWindow` bars (including the current bar)
        int endIdx = candles.size() - 1;
        int startIdx = endIdx - avgWindow + 1;

        for (int i = startIdx; i <= endIdx; i++) {
            double curr = candles.get(i).close();
            double prev = candles.get(i - period).close();
            if (prev != 0) {
                rocValues.add(((curr - prev) / prev) * 100.0);
            }
        }

        if (rocValues.isEmpty()) return Double.NaN;

        return rocValues.stream().mapToDouble(Double::doubleValue).average().orElse(Double.NaN);
    }
}
