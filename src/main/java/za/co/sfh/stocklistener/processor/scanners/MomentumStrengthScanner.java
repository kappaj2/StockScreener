package za.co.sfh.stocklistener.processor.scanners;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import za.co.sfh.stocklistener.payloads.AggregateMinuteBar;
import za.co.sfh.stocklistener.processor.PatternScanner;
import za.co.sfh.stocklistener.processor.indicators.LinearRegressionIndicator;
import za.co.sfh.stocklistener.processor.indicators.MacdIndicator;
import za.co.sfh.stocklistener.processor.indicators.RocIndicator;
import za.co.sfh.stocklistener.processor.indicators.RsiIndicator;
import za.co.sfh.stocklistener.processor.indicators.VolumeProfileIndicator;
import za.co.sfh.stocklistener.processor.ollama.OllamaStrengthScanner;
import za.co.sfh.stocklistener.processor.states.SymbolState;
import za.co.sfh.stocklistener.signals.BreakoutSignal;
import za.co.sfh.stocklistener.signals.PatternType;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Multi-indicator momentum strength scanner.
 *
 * <h2>Methodology</h2>
 * The scan follows a two-layer hierarchy:
 * <ol>
 *   <li><b>Signal identification (the math)</b> — each of the four technical indicators
 *       contributes a weighted score. A combination of MACD rising, RSI &gt; 60, ROC
 *       accelerating, and a positively-sloped linear regression must collectively cross a
 *       minimum threshold before a candidate signal is formed.</li>
 *   <li><b>Volume confirmation (the conviction)</b> — a volume surge must accompany the
 *       math signals. Without above-average volume the move is considered speculative and
 *       no signal is emitted.</li>
 * </ol>
 *
 * <h2>Scoring</h2>
 * <table>
 *   <tr><th>Condition</th><th>Points</th></tr>
 *   <tr><td>Linear Regression: steeply positive slope</td><td>2</td></tr>
 *   <tr><td>Linear Regression: strong trend (steep + R² ≥ threshold)</td><td>+1 bonus</td></tr>
 *   <tr><td>RSI: bullish momentum (RSI &gt; 50)</td><td>1</td></tr>
 *   <tr><td>RSI: strong momentum (RSI &gt; 60)</td><td>+1 bonus</td></tr>
 *   <tr><td>ROC: accelerating</td><td>1</td></tr>
 *   <tr><td>ROC: surge (current ROC &gt; avg × surgeMultiplier)</td><td>+1 bonus</td></tr>
 *   <tr><td>MACD: above zero line</td><td>1</td></tr>
 *   <tr><td>MACD: expanding histogram</td><td>1</td></tr>
 *   <tr><td>MACD: bullish crossover on this bar</td><td>2</td></tr>
 *   <tr><td>Volume: surge (≥ 1.5× baseline)</td><td>2</td></tr>
 *   <tr><td>Volume: strong conviction (≥ 2.0× baseline)</td><td>+1 bonus</td></tr>
 * </table>
 *
 * <h2>Strength tiers and signal emission</h2>
 * <ul>
 *   <li><b>VERY_HIGH</b> (score ≥ 10 AND volume confirms): signal emitted, risk = "low"</li>
 *   <li><b>HIGH</b> (score ≥ 7 AND volume confirms): signal emitted, risk = "medium"</li>
 *   <li><b>MODERATE / NEUTRAL</b>: no signal</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MomentumStrengthScanner implements PatternScanner {

    // ── Injected indicators ───────────────────────────────────────────────────

    private final RsiIndicator rsiIndicator;
    private final RocIndicator rocIndicator;
    private final MacdIndicator macdIndicator;
    private final LinearRegressionIndicator linRegIndicator;
    private final VolumeProfileIndicator volumeIndicator;
    private final OllamaStrengthScanner ollamaStrengthScanner;

    // ── Configuration ─────────────────────────────────────────────────────────

    @Value("${patterns.momentum.stop:0.97}")
    private double stopMultiplier;

    @Value("${patterns.momentum.target:1.06}")
    private double targetMultiplier;

    @Value("${patterns.momentum.storeSignal:true}")
    private boolean storeSignal;

    /** Score threshold for a HIGH tier signal (volume confirmation still required). */
    @Value("${patterns.momentum.high-score:7}")
    private int highScoreThreshold;

    /** Score threshold for a VERY_HIGH tier signal (volume confirmation still required). */
    @Value("${patterns.momentum.very-high-score:10}")
    private int veryHighScoreThreshold;

    /** RSI value above which "strong momentum" bonus is awarded (default 60). */
    @Value("${patterns.momentum.rsi-strong-threshold:60.0}")
    private double rsiStrongThreshold;

    @Value("${patterns.momentum.ollama-enabled:true}")
    private boolean ollamaEnabled;

    // ── PatternScanner contract ───────────────────────────────────────────────

    @Override
    public boolean shouldStore() {
        return storeSignal;
    }

    @Override
    public Optional<BreakoutSignal> scan(AggregateMinuteBar bar, SymbolState state) {
        List<AggregateMinuteBar> candles = state.getCandles();
        if (candles.size() < 20) return Optional.empty();

        // ── Long-only structural price guards ─────────────────────────────────
        // 1. Current candle must be bullish
        if (bar.close() <= bar.open()) return Optional.empty();

        // 2. Close must be above the 9-period EMA — below EMA9 means short-term downtrend
        if (state.getEma9() > 0 && bar.close() <= state.getEma9()) return Optional.empty();

        // 3. Close must be above VWAP — below VWAP signals bearish intraday bias
        if (state.getVwap() > 0 && bar.close() <= state.getVwap()) return Optional.empty();

        // 4. Price must be higher than it was 3 bars ago — rules out a green bounce inside a drawback
        AggregateMinuteBar threeBack = candles.get(candles.size() - 4);
        if (bar.close() <= threeBack.close()) return Optional.empty();

        // ── Layer 1: compute all indicators ───────────────────────────────────
        RsiIndicator.RsiResult            rsi    = rsiIndicator.compute(candles);
        RocIndicator.RocResult            roc    = rocIndicator.compute(candles);
        MacdIndicator.MacdResult          macd   = macdIndicator.compute(candles);
        LinearRegressionIndicator.LinRegResult linReg = linRegIndicator.compute(candles);
        VolumeProfileIndicator.VolumeProfileResult vol = volumeIndicator.compute(candles);

        // ── Layer 2: score ─────────────────────────────────────────────────────
        Score score = buildScore(rsi, roc, macd, linReg, vol);

        log.debug("[{}] Momentum score: {}/14 — signals: {}",
                bar.symbol(), score.total, String.join(", ", score.reasons));

        // ── Layer 3: volume gate + tier evaluation ─────────────────────────────
        MomentumTier tier = evaluate(score.total, vol);

        if (tier == MomentumTier.NEUTRAL || tier == MomentumTier.MODERATE) {
            return Optional.empty();
        }

        // ── Layer 4: build signal ──────────────────────────────────────────────
        int confidence = Math.min(100, (int) ((score.total / 14.0) * 100));
        String risk    = tier == MomentumTier.VERY_HIGH ? "low" : "medium";
        String notes   = tier.label + " | " + String.join("; ", score.reasons);

        // Fire Ollama async — result is observational only, does not gate signal emission.
        // Guard against null to allow construction without a Spring context (e.g. replay tests).
        if (ollamaStrengthScanner == null) {
            log.info("[{}] Momentum signal — tier={}, score={}, confidence={} | ollamaAssessment=SKIPPED (no Ollama in context)",
                    bar.symbol(), tier.label, score.total, confidence);
            return buildSignal(bar, state, tier, confidence, risk, notes);
        }

        if (tier == MomentumTier.HIGH || !ollamaEnabled) {
            return buildSignal(bar, state, tier, confidence, risk, notes);
        }

        //  Only ask Ollama if configured for VERY_HIGH tier, to conserve API calls. The extra confidence boost from a VERY_HIGH rating is where we expect Ollama's second opinion to add the most value.
        ollamaStrengthScanner.analyseAsync(
                bar.symbol(), tier.label, score.total,
                rsi, roc, macd, linReg, vol
        ).thenAccept(opinion ->
                log.info("[{}] Momentum signal — tier={}, score={}, confidence={} | ollamaAssessment={}, ollamaConfidence={}, ollamaReason={}",
                        bar.symbol(), tier.label, score.total, confidence,
                        opinion.assessment(), opinion.confidence(), opinion.reasoning())
        );

        return buildSignal(bar, state, tier, confidence, risk, notes);
    }

    private Optional<BreakoutSignal> buildSignal(AggregateMinuteBar bar, SymbolState state,
                                                  MomentumTier tier, int confidence,
                                                  String risk, String notes) {
        return Optional.of(new BreakoutSignal(
                UUID.randomUUID().toString(),
                bar.symbol(),
                tier.patternType,
                bar.close(),
                bar.close() * stopMultiplier,
                bar.close() * targetMultiplier,
                confidence,
                risk,
                notes,
                System.currentTimeMillis(),
                state.getPreMarketHigh(),
                state.getPreMarketLow(),
                null
        ));
    }

    // ── Scoring logic ─────────────────────────────────────────────────────────

    private Score buildScore(RsiIndicator.RsiResult rsi,
                             RocIndicator.RocResult roc,
                             MacdIndicator.MacdResult macd,
                             LinearRegressionIndicator.LinRegResult linReg,
                             VolumeProfileIndicator.VolumeProfileResult vol) {

        int total = 0;
        List<String> reasons = new ArrayList<>();

        // ── Trend (Linear Regression) ─────────────────────────────────────────
        if (linReg.isValid() && linReg.steeplyPositive()) {
            total += 2;
            reasons.add("LinReg:steep");
            if (linReg.strongTrend()) {
                total += 1;
                reasons.add("LinReg:highR2");
            }
        }

        // ── RSI ───────────────────────────────────────────────────────────────
        if (rsi.isValid() && rsi.bullishMomentum()) {          // RSI > 50
            total += 1;
            reasons.add("RSI:bullish(" + String.format("%.0f", rsi.rsi()) + ")");
            if (rsi.rsi() >= rsiStrongThreshold) {             // RSI ≥ 60
                total += 1;
                reasons.add("RSI:strong");
            }
        }

        // ── ROC ───────────────────────────────────────────────────────────────
        // roc.accelerating() = roc > avgRoc, which fires even when both values are negative.
        // Guard with roc > 0 to ensure we only score positive (upward) momentum.
        if (roc.isValid() && roc.roc() > 0 && roc.accelerating()) {
            total += 1;
            reasons.add("ROC:accelerating(" + String.format("%.1f", roc.roc()) + "%)");
            // surge = roc >= avgRoc * surgeFactor; also guard avgRoc > 0 so a negative
            // rolling average cannot produce a spurious "surge" reading.
            if (roc.avgRoc() > 0 && roc.surge()) {
                total += 1;
                reasons.add("ROC:surge");
            }
        }

        // ── MACD ──────────────────────────────────────────────────────────────
        if (macd.isValid()) {
            if (macd.aboveZero()) {
                total += 1;
                reasons.add("MACD:aboveZero");
            }
            if (macd.expandingHistogram()) {
                total += 1;
                reasons.add("MACD:expanding");
            }
            if (macd.bullishCrossover()) {
                total += 2;
                reasons.add("MACD:crossover");
            }
        }

        // ── Volume (confirmation layer) ───────────────────────────────────────
        if (vol.isValid() && vol.volumeSurge()) {
            total += 2;
            reasons.add("Vol:surge(" + String.format("%.1fx", vol.ratio()) + ")");
            if (vol.strongConviction()) {
                total += 1;
                reasons.add("Vol:conviction");
            }
        }

        return new Score(total, reasons);
    }

    private MomentumTier evaluate(int score, VolumeProfileIndicator.VolumeProfileResult vol) {
        boolean volumeConfirmed = vol.isValid() && vol.volumeSurge();

        if (score >= veryHighScoreThreshold && volumeConfirmed) return MomentumTier.VERY_HIGH;
        if (score >= highScoreThreshold && volumeConfirmed)     return MomentumTier.HIGH;
        if (score >= highScoreThreshold)                        return MomentumTier.MODERATE;
        return MomentumTier.NEUTRAL;
    }

    // ── Internal types ────────────────────────────────────────────────────────

    private record Score(int total, List<String> reasons) {}

    private enum MomentumTier {
        VERY_HIGH("VERY_HIGH_MOMENTUM_BREAKOUT", PatternType.MOMENTUM_VERY_HIGH),
        HIGH("HIGH_MOMENTUM_ALERT",              PatternType.MOMENTUM_HIGH),
        MODERATE("MODERATE_UPWARD_TREND",        null),
        NEUTRAL("NEUTRAL_OR_FALLING",            null);

        final String      label;
        final PatternType patternType;

        MomentumTier(String label, PatternType patternType) {
            this.label       = label;
            this.patternType = patternType;
        }
    }
}
