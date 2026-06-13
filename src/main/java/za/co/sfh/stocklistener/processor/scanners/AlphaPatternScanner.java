package za.co.sfh.stocklistener.processor.scanners;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import za.co.sfh.stocklistener.payloads.AggregateMinuteBar;
import za.co.sfh.stocklistener.persistence.entities.DailyBarSummaryEntity;
import za.co.sfh.stocklistener.persistence.repositories.DailyBarSummaryRepository;
import za.co.sfh.stocklistener.processor.PatternScanner;
import za.co.sfh.stocklistener.processor.states.SymbolState;
import za.co.sfh.stocklistener.signals.BreakoutSignal;
import za.co.sfh.stocklistener.signals.PatternType;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AlphaPatternScanner — detects institutional accumulation breakouts.
 *
 * The pattern has three sequential phases:
 *   1. Steep climb   — price advances ≥ minAdvancePct% within maxClimbDays trading days.
 *   2. Shallowing lows — after the peak, at least minBottoms successive swing lows,
 *      each higher than the previous (institutional buyers absorbing supply at higher floors).
 *   3. Breakout — intraday bar closes above the resistance high of the consolidation zone
 *      on volume ≥ volumeMultiplier × the symbol's average minute-bar volume.
 *
 * Setup state is computed once per symbol per trading day and cached intraday.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AlphaPatternScanner implements PatternScanner {

    private static final ZoneId ET = ZoneId.of("America/New_York");

    @Value("${patterns.alpha.target:1.20}")
    private double targetMultiplier;

    @Value("${patterns.alpha.storeSignal:false}")
    private boolean storeSignal;

    /** Minimum % price advance from base to peak to qualify as the steep climb phase. */
    @Value("${patterns.alpha.min-advance-pct:40.0}")
    private double minAdvancePct;

    /** Maximum number of trading days the climb phase may span. */
    @Value("${patterns.alpha.max-climb-days:60}")
    private int maxClimbDays;

    /** Total daily history window examined when searching for the pattern. */
    @Value("${patterns.alpha.lookback-days:90}")
    private int lookbackDays;

    /** Minimum number of sequential higher swing lows required after the climb. */
    @Value("${patterns.alpha.min-bottoms:3}")
    private int minBottoms;

    /**
     * Half-width (in bars) of the swing-low detection window.
     * A bar qualifies as a swing low when its low is strictly less than
     * every bar within swingWindow bars on each side.
     */
    @Value("${patterns.alpha.swing-window:3}")
    private int swingWindow;

    /** Intraday breakout bar volume must exceed avgVolume × this multiplier. */
    @Value("${patterns.alpha.volume-multiplier:1.5}")
    private double volumeMultiplier;

    private final DailyBarSummaryRepository dailyRepo;

    /** Per-symbol daily cache; a stale entry (different date) is recomputed on demand. */
    private final ConcurrentHashMap<String, AlphaDailyState> alphaCache = new ConcurrentHashMap<>();

    // -------------------------------------------------------------------------
    // PatternScanner contract
    // -------------------------------------------------------------------------

    @Override
    public boolean shouldStore() {
        return storeSignal;
    }

    @Override
    public Optional<BreakoutSignal> scan(AggregateMinuteBar bar, SymbolState state) {
        AlphaDailyState alpha = resolveAlphaState(bar.symbol());

        if (!alpha.valid()) {
            log.debug("[ALPHA] {} not in setup — {}", bar.symbol(), alpha.reason());
            return Optional.empty();
        }else{
            log.debug("[ALPHA] {} is in setup — {}", bar.symbol(), alpha.reason());
        }

        // Must close above the consolidation resistance level
        if (bar.close() <= alpha.resistanceLevel()) return Optional.empty();

        // Must show volume conviction vs. the session's average minute-bar volume
        if (bar.volume() < volumeMultiplier * state.getAvgVolume()) return Optional.empty();

        log.info("[ALPHA] Breakout confirmed [symbol={}; close={}; resistance={}; higherLows={}; volume={}]",
                bar.symbol(), bar.close(), alpha.resistanceLevel(), alpha.bottomCount(), bar.volume());

        return Optional.of(new BreakoutSignal(
                UUID.randomUUID().toString(),
                bar.symbol(),
                PatternType.ALPHA_PATTERN,
                bar.close(),
                alpha.lastBottomLow(),          // stop below the last confirmed higher low
                bar.close() * targetMultiplier,
                85,
                "stop below last higher low",
                String.format("Alpha pattern: steep climb + %d shallowing lows → breakout above %.2f",
                        alpha.bottomCount(), alpha.resistanceLevel()),
                bar.endTimestampMs().toInstant().toEpochMilli(),
                state.getPreMarketHigh(),
                state.getPreMarketLow(),
                null,
                false
        ));
    }

    // -------------------------------------------------------------------------
    // Daily setup computation
    // -------------------------------------------------------------------------

    private AlphaDailyState resolveAlphaState(String symbol) {
        LocalDate today = LocalDate.now(ET);
        AlphaDailyState cached = alphaCache.get(symbol);
        if (cached != null && cached.computedDate().equals(today)) {
            return cached;
        }
        AlphaDailyState fresh = computeAlphaState(symbol, today);
        alphaCache.put(symbol, fresh);
        return fresh;
    }

    private AlphaDailyState computeAlphaState(String symbol, LocalDate today) {
        LocalDate fromDate = today.minusDays(lookbackDays);
        // Exclude today — its daily bar is not yet closed
        List<DailyBarSummaryEntity> bars =
                dailyRepo.findBySymbolAndTradeDateBetweenOrderByTradeDateAsc(symbol, fromDate, today.minusDays(1));

        int minRequired = swingWindow * 2 + minBottoms + 10;
        if (bars.size() < minRequired) {
            return AlphaDailyState.invalid(today,
                    "[ALPHA] insufficient history: " + bars.size() + " bars (need " + minRequired + ")");
        }

        // --- Phase 1: locate the steep climb ---
        // For each candidate peak, look back up to maxClimbDays for the lowest base close.
        // Keep the peak that produced the greatest % advance above minAdvancePct.
        int climbPeakIdx = -1;
        double bestAdvancePct = 0;
        double peakHigh = 0;

        for (int end = 1; end < bars.size(); end++) {
            int start = Math.max(0, end - maxClimbDays);
            double baseClose = Double.MAX_VALUE;
            for (int i = start; i < end; i++) {
                double c = bars.get(i).getClosePrice().doubleValue();
                if (c < baseClose) baseClose = c;
            }
            double high = bars.get(end).getHighPrice().doubleValue();
            double advance = (high - baseClose) / baseClose * 100.0;
            if (advance > bestAdvancePct) {
                bestAdvancePct = advance;
                climbPeakIdx = end;
                peakHigh = high;
            }
        }

        if (climbPeakIdx < 0 || bestAdvancePct < minAdvancePct) {
            return AlphaDailyState.invalid(today,
                    String.format("[ALPHA] no steep climb — best advance %.1f%% < required %.1f%%",
                            bestAdvancePct, minAdvancePct));
        }

        // --- Phase 2: detect shallowing swing lows after the climb peak ---
        List<DailyBarSummaryEntity> postClimb = bars.subList(climbPeakIdx, bars.size());

        if (postClimb.size() < swingWindow * 2 + 1) {
            return AlphaDailyState.invalid(today,
                    "only " + postClimb.size() + " bars after climb peak — need " + (swingWindow * 2 + 1));
        }

        // Identify swing lows: bar[i].low strictly less than every bar within swingWindow on each side
        List<Double> swingLows = new ArrayList<>();
        for (int i = swingWindow; i < postClimb.size() - swingWindow; i++) {
            double low = postClimb.get(i).getLowPrice().doubleValue();
            boolean isSwingLow = true;
            for (int j = i - swingWindow; j <= i + swingWindow; j++) {
                if (j == i) continue;
                if (postClimb.get(j).getLowPrice().doubleValue() <= low) {
                    isSwingLow = false;
                    break;
                }
            }
            if (isSwingLow) swingLows.add(low);
        }

        if (swingLows.size() < minBottoms) {
            return AlphaDailyState.invalid(today,
                    "only " + swingLows.size() + " swing lows found — need " + minBottoms);
        }

        // Count the longest run of sequential higher lows
        int bestRun = 1;
        int currentRun = 1;
        for (int i = 1; i < swingLows.size(); i++) {
            if (swingLows.get(i) > swingLows.get(i - 1)) {
                currentRun++;
                if (currentRun > bestRun) bestRun = currentRun;
            } else {
                currentRun = 1;
            }
        }

        if (bestRun < minBottoms) {
            return AlphaDailyState.invalid(today,
                    "longest sequential higher-low run is " + bestRun + " — need " + minBottoms);
        }

        // --- Phase 3: define resistance and stop levels ---
        // Resistance = highest intraday high recorded during the post-climb consolidation
        double resistance = postClimb.stream()
                .mapToDouble(b -> b.getHighPrice().doubleValue())
                .max().orElse(peakHigh);

        // Stop = the last swing low (deepest institutional support floor)
        double lastBottomLow = swingLows.getLast();

        log.debug("[ALPHA] {} setup valid — advance={}%, resistance={}, higherLows={}, lastLow={}",
                symbol,
                String.format("%.1f", bestAdvancePct),
                resistance,
                bestRun,
                lastBottomLow);

        return new AlphaDailyState(today, true, resistance, lastBottomLow, bestRun, "valid");
    }

    // -------------------------------------------------------------------------
    // Cached state record
    // -------------------------------------------------------------------------

    record AlphaDailyState(
            LocalDate computedDate,
            boolean valid,
            double resistanceLevel,
            double lastBottomLow,
            int bottomCount,
            String reason
    ) {
        static AlphaDailyState invalid(LocalDate date, String reason) {
            return new AlphaDailyState(date, false, 0.0, 0.0, 0, reason);
        }
    }
}
