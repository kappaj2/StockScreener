package za.co.sfh.stocklistener.processor.scanners.scalping.smb;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import za.co.sfh.stocklistener.payloads.FiveMinuteBar;
import za.co.sfh.stocklistener.persistence.entities.DailyBarSummaryEntity;
import za.co.sfh.stocklistener.persistence.repositories.DailyBarSummaryRepository;
import za.co.sfh.stocklistener.processor.scanners.scalping.ScalpingPatternScanner;
import za.co.sfh.stocklistener.processor.states.FiveMinuteState;
import za.co.sfh.stocklistener.signals.BreakoutSignal;
import za.co.sfh.stocklistener.signals.PatternType;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Breakout Without Catalyst — detects a stock approaching a well-tested resistance level
 * and breaking out on elevated relative volume, without a news event driving the move.
 *
 * <p>Three checks, in order:
 * <ol>
 *   <li><b>Resistance strength (0–10):</b> Scans the last {@code lookbackDays} of daily bars to
 *       find how many times price approached and rejected the level. Score is based on touch
 *       count, tightness of the range around the level, and recency. Must be ≥
 *       {@code minResistanceScore}.</li>
 *   <li><b>Pre-breakout RVOL > 1.0:</b> At least one of the two bars immediately before the
 *       current bar must carry RVOL > {@code minPreBreakoutRvol}, indicating institutional
 *       accumulation building toward the level.</li>
 *   <li><b>Breakout bar volume surge:</b> The current bar's volume must exceed the average
 *       bar volume by at least {@code breakoutVolumeMultiple}×, confirming conviction on
 *       the break.</li>
 * </ol>
 *
 * <p>Stop is placed at the resistance level (now acting as support).
 * Target is entry + (entry - stop) × {@code rewardRatio}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BreakoutNoCatalystScanner implements ScalpingPatternScanner {

    private static final ZoneId ET = ZoneId.of("America/New_York");

    @Value("${scalping.smb.breakout-no-catalyst.enabled:true}")
    private boolean enabled;

    // Minimum resistance score (0–10) to consider the level significant
    @Value("${scalping.smb.breakout-no-catalyst.min-resistance-score:6}")
    private int minResistanceScore;

    // How many calendar days of daily bars to scan for resistance touches
    @Value("${scalping.smb.breakout-no-catalyst.lookback-days:60}")
    private int lookbackDays;

    // Resistance zone tolerance: how close (as % of price) a daily high must be to count as a "touch"
    @Value("${scalping.smb.breakout-no-catalyst.resistance-zone-pct:0.5}")
    private double resistanceZonePct;

    // Pre-breakout bars (the two bars before entry) must have RVOL above this threshold
    @Value("${scalping.smb.breakout-no-catalyst.min-pre-breakout-rvol:1.0}")
    private double minPreBreakoutRvol;

    // Breakout bar volume must be at least this multiple of the session average volume
    @Value("${scalping.smb.breakout-no-catalyst.breakout-volume-multiple:1.5}")
    private double breakoutVolumeMultiple;

    @Value("${scalping.smb.breakout-no-catalyst.reward-ratio:2.0}")
    private double rewardRatio;

    private final DailyBarSummaryRepository dailyRepo;

    // Cache: symbol → resistance analysis, recomputed once per session date
    private final ConcurrentHashMap<String, ResistanceCache> resistanceCache = new ConcurrentHashMap<>();

    @Override
    public Optional<BreakoutSignal> scan(FiveMinuteBar bar, FiveMinuteState state) {
        if (!enabled) return Optional.empty();

        List<FiveMinuteBar> bars = state.getBars();
        // Need at least 3 bars: 2 pre-breakout bars + the current breakout bar
        if (bars.size() < 3) return Optional.empty();

        FiveMinuteBar entryBar = bars.getLast();
        FiveMinuteBar preBar1  = bars.get(bars.size() - 2); // bar immediately before entry
        FiveMinuteBar preBar2  = bars.get(bars.size() - 3); // bar two before entry

        // Only fire on a green breakout bar
        if (!entryBar.isGreen()) {
            log.debug("[BNC] [{}] entry bar not green — skipping", bar.symbol());
            return Optional.empty();
        }

        // Step 1 — resistance level scoring from daily history
        LocalDate today = bar.endTime().withZoneSameInstant(ET).toLocalDate();
        ResistanceCache cached = resolveResistance(bar.symbol(), today);

        if (cached.score() < minResistanceScore) {
            log.debug("[BNC] [{}] resistance score {} < min {} — skipping",
                    bar.symbol(), cached.score(), minResistanceScore);
            return Optional.empty();
        }

        double resistanceLevel = cached.level();

        // The breakout bar's close must be above the resistance level
        if (entryBar.close() <= resistanceLevel) {
            log.debug("[BNC] [{}] entry close {:.4f} not above resistance {:.4f} — skipping",
                    bar.symbol(), entryBar.close(), resistanceLevel);
            return Optional.empty();
        }

        // The resistance level must be within the breakout bar's range (price passed through it)
        if (entryBar.low() > resistanceLevel) {
            log.debug("[BNC] [{}] resistance level {:.4f} not within bar range [{:.4f}-{:.4f}] — skipping",
                    bar.symbol(), resistanceLevel, entryBar.low(), entryBar.high());
            return Optional.empty();
        }

        // Step 2 — elevated pre-breakout RVOL on at least one of the two preceding bars
        boolean preRvolOk = (preBar1.hasRvol() && preBar1.rvol() >= minPreBreakoutRvol)
                || (preBar2.hasRvol() && preBar2.rvol() >= minPreBreakoutRvol);
        if (!preRvolOk) {
            log.debug("[BNC] [{}] pre-breakout RVOL not elevated (bar-1={} bar-2={}) — skipping",
                    bar.symbol(),
                    preBar1.hasRvol() ? String.format("%.2f", preBar1.rvol()) : "n/a",
                    preBar2.hasRvol() ? String.format("%.2f", preBar2.rvol()) : "n/a");
            return Optional.empty();
        }

        // Step 3 — volume surge on the breakout bar itself
        double avgVol = state.getAvgVolume();
        if (avgVol > 0 && entryBar.volume() < breakoutVolumeMultiple * avgVol) {
            log.debug("[BNC] [{}] breakout bar volume {} < {:.1f}× avg ({:.0f}) — skipping",
                    bar.symbol(), entryBar.volume(), breakoutVolumeMultiple, avgVol);
            return Optional.empty();
        }

        double entry  = entryBar.close();
        double stop   = resistanceLevel;   // level flips to support after break
        if (stop >= entry) return Optional.empty();

        double risk   = entry - stop;
        double target = entry + risk * rewardRatio;

        double preRvol = preBar1.hasRvol() ? preBar1.rvol() : preBar2.rvol();

        log.info("[BNC] [{}] Breakout No Catalyst — resistance={} score={} entry={} stop={} target={} " +
                        "preRvol={} breakoutVol={} avgVol={}",
                bar.symbol(),
                String.format("%.4f", resistanceLevel),
                cached.score(),
                String.format("%.4f", entry),
                String.format("%.4f", stop),
                String.format("%.4f", target),
                String.format("%.2f", preRvol),
                entryBar.volume(),
                String.format("%.0f", avgVol));

        return Optional.of(new BreakoutSignal(
                UUID.randomUUID().toString(),
                bar.symbol(),
                PatternType.BREAKOUT_NO_CATALYST,
                entry,
                stop,
                target,
                scoreToConfidence(cached.score()),
                "low",
                String.format("BNC: resistance=%.4f score=%d touches=%d preRvol=%.2f breakoutVol=%d avgVol=%.0f R:R=1:%.1f",
                        resistanceLevel, cached.score(), cached.touches(), preRvol,
                        entryBar.volume(), avgVol, rewardRatio),
                entryBar.endTime().toInstant().toEpochMilli(),
                0,
                0,
                null,
                false,
                bar.rvol()
        ));
    }

    // -------------------------------------------------------------------------
    // Resistance scoring
    // -------------------------------------------------------------------------

    private ResistanceCache resolveResistance(String symbol, LocalDate today) {
        ResistanceCache cached = resistanceCache.get(symbol);
        if (cached != null && cached.computedDate().equals(today)) {
            return cached;
        }
        ResistanceCache fresh = computeResistance(symbol, today);
        resistanceCache.put(symbol, fresh);
        return fresh;
    }

    /**
     * Computes the most significant resistance level from daily bar history and scores it 0–10.
     *
     * <p>Algorithm:
     * <ol>
     *   <li>Collect daily highs over the last {@code lookbackDays} calendar days.</li>
     *   <li>Group highs that fall within {@code resistanceZonePct}% of each other into clusters.</li>
     *   <li>Pick the largest cluster (most touches) as the resistance level (mean of cluster).</li>
     *   <li>Score: base score from touch count (each touch = 2 pts, max 6), plus recency bonus
     *       (last touch within 5 days = +2, 10 days = +1), plus tightness bonus (range within
     *       0.25% = +2, 0.5% = +1).</li>
     * </ol>
     */
    private ResistanceCache computeResistance(String symbol, LocalDate today) {
        LocalDate from = today.minusDays(lookbackDays);
        List<DailyBarSummaryEntity> history =
                dailyRepo.findBySymbolAndTradeDateBetweenOrderByTradeDateAsc(symbol, from, today.minusDays(1));

        if (history.size() < 5) {
            log.debug("[BNC] [{}] insufficient daily history: {} days", symbol, history.size());
            return ResistanceCache.none(today);
        }

        record DailyHigh(LocalDate date, double high) {}
        List<DailyHigh> highs = history.stream()
                .map(b -> new DailyHigh(b.getTradeDate(), b.getHighPrice().doubleValue()))
                .toList();

        double bestLevel    = 0;
        int    bestTouches  = 0;
        double bestRange    = Double.MAX_VALUE;
        LocalDate bestLastTouch = null;

        for (int i = 0; i < highs.size(); i++) {
            double pivot         = highs.get(i).high();
            double zoneTolerance = pivot * (resistanceZonePct / 100.0);

            int touches       = 0;
            double clusterSum = 0;
            double clusterMin = Double.MAX_VALUE;
            double clusterMax = Double.MIN_VALUE;
            LocalDate lastTouch = null;

            for (DailyHigh dh : highs) {
                if (Math.abs(dh.high() - pivot) <= zoneTolerance) {
                    touches++;
                    clusterSum += dh.high();
                    if (dh.high() < clusterMin) clusterMin = dh.high();
                    if (dh.high() > clusterMax) clusterMax = dh.high();
                    if (lastTouch == null || dh.date().isAfter(lastTouch)) lastTouch = dh.date();
                }
            }

            double clusterRange = clusterMax - clusterMin;

            if (touches > bestTouches || (touches == bestTouches && clusterRange < bestRange)) {
                bestTouches   = touches;
                bestLevel     = clusterSum / touches;
                bestRange     = clusterRange;
                bestLastTouch = lastTouch;
            }
        }

        if (bestTouches < 2) {
            log.debug("[BNC] [{}] no meaningful resistance cluster found (max touches={})", symbol, bestTouches);
            return ResistanceCache.none(today);
        }

        int score = scoreResistance(bestTouches, bestRange, bestLevel, bestLastTouch, today);

        log.debug("[BNC] [{}] resistance level={} touches={} range={} lastTouch={} score={}",
                symbol,
                String.format("%.4f", bestLevel),
                bestTouches,
                String.format("%.4f", bestRange),
                bestLastTouch,
                score);

        return new ResistanceCache(today, bestLevel, score, bestTouches);
    }

    private int scoreResistance(int touches, double range, double level, LocalDate lastTouch, LocalDate today) {
        int score = 0;

        // Touch count: 2 pts per touch, max 6 (3+ touches already significant)
        score += Math.min(6, touches * 2);

        // Recency: was the level tested recently?
        if (lastTouch != null) {
            long daysAgo = ChronoUnit.DAYS.between(lastTouch, today);
            if (daysAgo <= 5)       score += 2;
            else if (daysAgo <= 10) score += 1;
        }

        // Tightness: how compressed is the cluster around the level?
        if (level > 0) {
            double rangePct = (range / level) * 100.0;
            if (rangePct <= 0.25)      score += 2;
            else if (rangePct <= 0.50) score += 1;
        }

        return Math.min(10, score);
    }

    private int scoreToConfidence(int resistanceScore) {
        // Map 0–10 resistance score to 50–95 confidence
        return 50 + (int) Math.round(resistanceScore * 4.5);
    }

    // -------------------------------------------------------------------------
    // Internal state
    // -------------------------------------------------------------------------

    record ResistanceCache(LocalDate computedDate, double level, int score, int touches) {
        static ResistanceCache none(LocalDate date) {
            return new ResistanceCache(date, 0, 0, 0);
        }
    }
}