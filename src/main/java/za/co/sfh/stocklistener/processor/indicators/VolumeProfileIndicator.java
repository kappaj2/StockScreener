package za.co.sfh.stocklistener.processor.indicators;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import za.co.sfh.stocklistener.payloads.AggregateMinuteBar;

import java.util.List;

/**
 * Volume Profile indicator.
 *
 * <p>Compares the average volume of a recent short window against the average volume of a longer
 * baseline window. A price climb accompanied by above-average volume indicates institutional
 * participation and increases the probability that the move is sustainable.
 *
 * <h3>Derived signals</h3>
 * <ul>
 *   <li><b>Volume surge</b>: short-window average ≥ {@code surgeThreshold} × baseline average
 *       (default 1.5×). Flags above-average interest.</li>
 *   <li><b>Strong conviction</b>: short-window average ≥ {@code convictionThreshold} × baseline
 *       average (default 2.0×). Flags likely institutional participation.</li>
 *   <li><b>Declining volume</b>: short-window average &lt; baseline average. Warns that a price
 *       move may lack follow-through.</li>
 * </ul>
 *
 * <p>When fewer bars are available than {@code longPeriod} the long baseline gracefully degrades
 * to all available history. If fewer bars than {@code shortPeriod} are available, an empty result
 * is returned.
 *
 * <p>Note: the {@code SymbolState} already tracks {@code avgVolume} over the rolling 20-bar window,
 * but that window is fixed. This indicator provides configurable short/long windows and exposes the
 * derived conviction signals directly to scanners.
 */
@Slf4j
@Component
public class VolumeProfileIndicator {

    /**
     * Number of bars in the recent (short) window; override via {@code indicators.volume.short-period}.
     * Default of 60 covers the last full trading hour.
     */
    @Value("${indicators.volume.short-period:60}")
    private int shortPeriod;

    /**
     * Maximum number of bars used for the long baseline; override via {@code indicators.volume.long-period}.
     * Default of 390 covers a full regular trading session (6.5 h × 60).
     */
    @Value("${indicators.volume.long-period:390}")
    private int longPeriod;

    /**
     * Ratio of short-window average to long-window average that defines a "volume surge";
     * override via {@code indicators.volume.surge-threshold}.
     */
    @Value("${indicators.volume.surge-threshold:1.5}")
    private double surgeThreshold;

    /**
     * Ratio of short-window average to long-window average that defines "strong conviction";
     * override via {@code indicators.volume.conviction-threshold}.
     */
    @Value("${indicators.volume.conviction-threshold:2.0}")
    private double convictionThreshold;

    // ── Result record ─────────────────────────────────────────────────────────

    /**
     * Volume profile result.
     *
     * @param shortAvg          Average volume over the last {@code shortPeriod} bars.
     * @param longAvg           Average volume over the baseline window (up to {@code longPeriod} bars).
     * @param ratio             {@code shortAvg / longAvg}. Values &gt; 1.0 indicate above-average activity.
     * @param volumeSurge       {@code true} when {@code ratio ≥ surgeThreshold} (default 1.5×).
     * @param strongConviction  {@code true} when {@code ratio ≥ convictionThreshold} (default 2.0×).
     * @param decliningVolume   {@code true} when {@code ratio < 1.0} — current activity lags the baseline.
     * @param shortWindowBars   Actual number of bars in the short window.
     * @param longWindowBars    Actual number of bars in the long baseline.
     */
    public record VolumeProfileResult(double shortAvg, double longAvg, double ratio,
                                      boolean volumeSurge, boolean strongConviction,
                                      boolean decliningVolume,
                                      int shortWindowBars, int longWindowBars) {

        /** Sentinel returned when there are not enough bars for the short window. */
        public static VolumeProfileResult empty() {
            return new VolumeProfileResult(Double.NaN, Double.NaN, Double.NaN,
                    false, false, false, 0, 0);
        }

        public boolean isValid() {
            return !Double.isNaN(ratio);
        }
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Computes the volume profile for the most recent bar.
     *
     * @param candles ordered list of bars, oldest first (as returned by {@code SymbolState.getCandles()})
     * @return {@link VolumeProfileResult} with short/long averages, ratio, and derived signals,
     *         or {@link VolumeProfileResult#empty()} if fewer than {@code shortPeriod} bars are available
     */
    public VolumeProfileResult compute(List<AggregateMinuteBar> candles) {
        if (candles.size() < shortPeriod) {
            log.debug("VolumeProfile: insufficient bars ({} < {})", candles.size(), shortPeriod);
            return VolumeProfileResult.empty();
        }

        // ── Short window (recent activity) ─────────────────────────────────────────
        List<AggregateMinuteBar> shortWindow = candles.subList(
                candles.size() - shortPeriod, candles.size());

        double shortAvg = shortWindow.stream()
                .mapToLong(AggregateMinuteBar::volume)
                .average()
                .orElse(0);

        // ── Long baseline (may be smaller than longPeriod early in the session) ────
        int longN = Math.min(longPeriod, candles.size());
        List<AggregateMinuteBar> longWindow = candles.subList(
                candles.size() - longN, candles.size());

        double longAvg = longWindow.stream()
                .mapToLong(AggregateMinuteBar::volume)
                .average()
                .orElse(0);

        if (longAvg == 0) {
            log.debug("VolumeProfile: baseline average is zero — cannot compute ratio");
            return VolumeProfileResult.empty();
        }

        double ratio = shortAvg / longAvg;

        boolean volumeSurge      = ratio >= surgeThreshold;
        boolean strongConviction = ratio >= convictionThreshold;
        boolean decliningVolume  = ratio < 1.0;

        log.debug("VolumeProfile computed: shortAvg={:.0f}, longAvg={:.0f}, ratio={:.2f}, surge={}, conviction={}, declining={}",
                shortAvg, longAvg, ratio, volumeSurge, strongConviction, decliningVolume);

        return new VolumeProfileResult(shortAvg, longAvg, ratio,
                volumeSurge, strongConviction, decliningVolume,
                shortPeriod, longN);
    }
}
