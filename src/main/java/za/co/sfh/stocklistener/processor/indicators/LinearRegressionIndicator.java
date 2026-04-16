package za.co.sfh.stocklistener.processor.indicators;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import za.co.sfh.stocklistener.payloads.AggregateMinuteBar;

import java.util.List;

/**
 * Linear Regression (slope) indicator.
 *
 * <p>Fits an Ordinary Least Squares (OLS) line through the closing prices of the last
 * {@code period} bars, using bar index (0, 1, …, N−1) as the x-axis. The slope of this
 * best-fit line represents the average per-bar price change over the window and is the
 * most objective measure of trend direction and steepness.
 *
 * <h3>Derived signals</h3>
 * <ul>
 *   <li><b>Steeply positive</b>: slope is positive <em>and</em> the slope-to-price ratio exceeds
 *       {@code steepThreshold} (default 0.1 % per bar). This filters out noise when the price
 *       drifts fractionally upward.</li>
 *   <li><b>R²</b>: coefficient of determination in [0, 1]. Values closer to 1.0 indicate that the
 *       price movement over the window is highly linear — a sustained, directional move rather than
 *       random chop.</li>
 *   <li><b>Strong trend</b>: {@code steeplyPositive && r2 ≥ r2Threshold} — both direction and
 *       linearity confirm a reliable climb.</li>
 * </ul>
 *
 * <p>At least 2 bars are required; when fewer bars are available than {@code period} the window
 * is capped at the available history (graceful degradation).
 */
@Slf4j
@Component
public class LinearRegressionIndicator {

    /** Number of bars used for the regression window; override via {@code indicators.linreg.period}. */
    @Value("${indicators.linreg.period:60}")
    private int period;

    /**
     * Minimum normalised slope (slope / meanClose) for the trend to be classified as "steep";
     * override via {@code indicators.linreg.steep-threshold}.
     *
     * <p>Default of 0.001 means the average per-bar gain must be at least 0.1 % of the stock price.
     */
    @Value("${indicators.linreg.steep-threshold:0.001}")
    private double steepThreshold;

    /**
     * Minimum R² required alongside a positive slope for the combined "strong trend" flag;
     * override via {@code indicators.linreg.r2-threshold}.
     */
    @Value("${indicators.linreg.r2-threshold:0.70}")
    private double r2Threshold;

    // ── Result record ─────────────────────────────────────────────────────────

    /**
     * Linear regression result over the look-back window.
     *
     * @param slope           OLS slope (price change per bar). Positive = uptrend, negative = downtrend.
     * @param intercept       OLS intercept (fitted price at bar index 0 of the window).
     * @param r2              Coefficient of determination in [0, 1]. Higher = more linear / directional.
     * @param steeplyPositive {@code true} when slope is positive and normalised slope ≥ steep threshold.
     * @param strongTrend     {@code true} when the move is both steeply positive and highly linear
     *                        ({@code r2 ≥ r2Threshold}).
     * @param windowSize      Actual number of bars used (may be less than {@code period} early on).
     */
    public record LinRegResult(double slope, double intercept, double r2,
                               boolean steeplyPositive, boolean strongTrend, int windowSize) {

        /** Sentinel returned when fewer than 2 bars are available. */
        public static LinRegResult empty() {
            return new LinRegResult(Double.NaN, Double.NaN, Double.NaN, false, false, 0);
        }

        public boolean isValid() {
            return !Double.isNaN(slope);
        }

        /** Projected close for the <em>next</em> bar based on the fitted line. */
        public double projectNextBar() {
            if (!isValid()) return Double.NaN;
            return slope * windowSize + intercept;
        }
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Fits an OLS line through the closing prices of the most recent {@code period} bars.
     *
     * @param candles ordered list of bars, oldest first (as returned by {@code SymbolState.getCandles()})
     * @return {@link LinRegResult} with slope, intercept, R², and derived trend signals,
     *         or {@link LinRegResult#empty()} if fewer than 2 bars are available
     */
    public LinRegResult compute(List<AggregateMinuteBar> candles) {
        int n = Math.min(period, candles.size());
        if (n < 2) {
            log.debug("LinReg: insufficient bars ({})", candles.size());
            return LinRegResult.empty();
        }

        List<AggregateMinuteBar> window = candles.subList(candles.size() - n, candles.size());

        // ── Compute OLS sums ───────────────────────────────────────────────────────
        double sumX = 0, sumY = 0, sumXY = 0, sumX2 = 0;
        for (int i = 0; i < n; i++) {
            double x = i;
            double y = window.get(i).close();
            sumX  += x;
            sumY  += y;
            sumXY += x * y;
            sumX2 += x * x;
        }

        double denominator = (double) n * sumX2 - sumX * sumX;
        if (denominator == 0) {
            // All x values are identical — degenerate case (single bar)
            return LinRegResult.empty();
        }

        double slope     = (n * sumXY - sumX * sumY) / denominator;
        double intercept = (sumY - slope * sumX) / n;

        // ── R² ──────────────────────────────────────────────────────────────────
        double meanY    = sumY / n;
        double ssTotal  = 0;
        double ssResidual = 0;
        for (int i = 0; i < n; i++) {
            double y         = window.get(i).close();
            double predicted = slope * i + intercept;
            ssTotal   += (y - meanY) * (y - meanY);
            ssResidual += (y - predicted) * (y - predicted);
        }
        double r2 = ssTotal > 0 ? 1.0 - ssResidual / ssTotal : 1.0;

        // ── Derived signals ────────────────────────────────────────────────────
        // Normalise slope by mean price to make the threshold scale-independent
        boolean steeplyPositive = slope > 0 && meanY > 0 && (slope / meanY) >= steepThreshold;
        boolean strongTrend     = steeplyPositive && r2 >= r2Threshold;

        log.debug("LinReg computed: slope={:.5f}, r2={:.3f}, steep={}, strong={} (n={})",
                slope, r2, steeplyPositive, strongTrend, n);

        return new LinRegResult(slope, intercept, r2, steeplyPositive, strongTrend, n);
    }
}
