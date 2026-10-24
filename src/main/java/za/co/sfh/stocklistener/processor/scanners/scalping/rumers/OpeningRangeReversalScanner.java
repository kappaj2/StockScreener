package za.co.sfh.stocklistener.processor.scanners.scalping.rumers;

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
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Opening Range Reversal — long-only scalping setup on the 5-minute chart.
 *
 * <pre>
 * Trend bias (daily):
 *   Price must be above the 50-day SMA → buyers in control, dips to be bought.
 *
 * Opening range (first three 5-min bars = first 15 minutes):
 *   - All three bars must be red (down candles) — manipulation / liquidity sweep.
 *   - The 15-min opening range is the composite high (max wick high) and low (min wick low)
 *     of those three bars.
 *   - The opening range move (high − low) must be ≥ minAtrPct % of the 14-bar ATR — anything
 *     smaller is noise, not a manipulation candle.
 *
 * Entry (engulfing reversal bar, 4th bar onward):
 *   - The first green bar whose body engulfs the prior bar's full range (high to low) is
 *     the entry candle.
 *   - Target = opening range high (top of the 15-min sweep).
 *   - Stop  = opening range low (below the wick of the sweep).
 * </pre>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OpeningRangeReversalScanner implements ScalpingPatternScanner {

    private static final ZoneId ET = ZoneId.of("America/New_York");

    @Value("${scalping.rumers.opening-range-reversal.enabled:true}")
    private boolean enabled;

    @Value("${scalping.rumers.opening-range-reversal.sma-period:50}")
    private int smaPeriod;

    // Opening range move must be at least this fraction of the 14-bar ATR (0.25 = 25%)
    @Value("${scalping.rumers.opening-range-reversal.min-atr-pct:0.25}")
    private double minAtrPct;

    private final DailyBarSummaryRepository dailyRepo;

    // Per-symbol daily state; invalidated at session boundary
    private final ConcurrentHashMap<String, DailyBias> biasCache = new ConcurrentHashMap<>();

    @Override
    public Optional<BreakoutSignal> scan(FiveMinuteBar bar, FiveMinuteState state) {
        if (!enabled) return Optional.empty();

        // Need at least 4 bars: 3 opening range bars + current (potential entry) bar
        List<FiveMinuteBar> bars = state.getBars();
        if (bars.size() < 4) return Optional.empty();

        // 1. Locate the true opening range bars by timestamp — always 09:30, 09:35, 09:40
        FiveMinuteBar or1 = findBarAtMinute(bars, 30);
        FiveMinuteBar or2 = findBarAtMinute(bars, 35);
        FiveMinuteBar or3 = findBarAtMinute(bars, 40);
        if (or1 == null || or2 == null || or3 == null) {
            log.debug("[ORR] [{}] opening range bars (09:30/35/40) not yet available", bar.symbol());
            return Optional.empty();
        }

        // 2. All three opening range bars must be red
        if (!or1.isRed() || !or2.isRed() || !or3.isRed()) {
            log.debug("[ORR] [{}] opening range bars not all red — skipping", bar.symbol());
            return Optional.empty();
        }

        // 3. Trend filter: price must be above 50-day SMA
        DailyBias bias = resolveBias(bar.symbol(), bar.endTime().toLocalDate());
        if (!bias.aboveSma50()) {
            log.debug("[ORR] [{}] price below 50-day SMA ({}) — long bias not met", bar.symbol(), bias.sma50());
            return Optional.empty();
        }

        // 4. Opening range = composite high/low across all three bars (including wicks)
        double orHigh = Math.max(or1.high(), Math.max(or2.high(), or3.high()));
        double orLow  = Math.min(or1.low(),  Math.min(or2.low(),  or3.low()));
        double orMove = orHigh - orLow;

        // 5. Opening range move must be at least minAtrPct × ATR — rules out noise
        double atr = state.getAtr();
        if (atr > 0 && orMove < minAtrPct * atr) {
            log.debug("[ORR] [{}] opening range move {} < {}×ATR ({}) — not a manipulation candle",
                    bar.symbol(),
                    String.format("%.4f", orMove),
                    minAtrPct,
                    String.format("%.4f", atr));
            return Optional.empty();
        }

        // 6. Every bar from or3 up to (but not including) the current bar must be red.
        //    This allows 4, 5, 6+ consecutive down bars — the OR stays fixed at the first three;
        //    the reversal is valid whenever the first green engulfing bar appears.
        FiveMinuteBar entry = bars.getLast();
        FiveMinuteBar priorToEntry = bars.get(bars.size() - 2);

        int or3Index = bars.indexOf(or3);
        for (int i = or3Index + 1; i < bars.size() - 1; i++) {
            if (!bars.get(i).isRed()) {
                log.debug("[ORR] [{}] bar at index {} is not red — down-run broken before entry", bar.symbol(), i);
                return Optional.empty();
            }
        }

        // 7. Entry bar must be green and must engulf the immediately preceding bar's full range
        if (!entry.isGreen()) {
            log.debug("[ORR] [{}] entry bar not green", bar.symbol());
            return Optional.empty();
        }
        if (!engulfs(entry, priorToEntry)) {
            log.debug("[ORR] [{}] entry bar does not engulf prior bar (H={} L={} vs entry O={} C={})",
                    bar.symbol(), priorToEntry.high(), priorToEntry.low(), entry.open(), entry.close());
            return Optional.empty();
        }

        // 8. Entry bar must not close above the opening range high — that is the target, not the entry
        if (entry.close() >= orHigh) {
            log.debug("[ORR] [{}] entry bar already at/above opening range high — target already hit, skipping",
                    bar.symbol());
            return Optional.empty();
        }

        double entryPrice = entry.close();

        if (orLow >= entryPrice) {
            log.debug("[ORR] [{}] degenerate stop >= entry — skipping", bar.symbol());
            return Optional.empty();
        }

        double risk = entryPrice - orLow;
        double rr   = (orHigh - entryPrice) / risk;
        int downBars = bars.size() - 1 - or3Index; // bars between or3 and entry (exclusive)

        log.info("[ORR] [{}] Opening Range Reversal — entry={} stop={} target={} orMove={} atr={} sma50={} R:R=1:{} downBars={}",
                bar.symbol(),
                String.format("%.4f", entryPrice),
                String.format("%.4f", orLow),
                String.format("%.4f", orHigh),
                String.format("%.4f", orMove),
                String.format("%.4f", atr),
                String.format("%.4f", bias.sma50()),
                String.format("%.2f", rr),
                downBars);

        return Optional.of(new BreakoutSignal(
                UUID.randomUUID().toString(),
                bar.symbol(),
                PatternType.OPENING_RANGE_REVERSAL,
                entryPrice,
                orLow,
                orHigh,
                80,
                "low",
                String.format("ORR: orHigh=%.4f orLow=%.4f orMove=%.4f ATR=%.4f sma50=%.4f R:R=1:%.2f downBars=%d",
                        orHigh, orLow, orMove, atr, bias.sma50(), rr, downBars),
                entry.endTime().toInstant().toEpochMilli(),
                0,
                0,
                null,
                false
        ));
    }

    /**
     * Returns true when the entry bar's open-to-close body engulfs the prior bar's full wick range.
     * A true engulfing candle opens at or below the prior low and closes at or above the prior high.
     */
    private boolean engulfs(FiveMinuteBar entry, FiveMinuteBar prior) {
        return entry.open() <= prior.low() && entry.close() >= prior.high();
    }

    /** Returns the bar whose start time is 09:xx ET (xx == minute), or null if not yet in the list. */
    private FiveMinuteBar findBarAtMinute(List<FiveMinuteBar> bars, int minute) {
        for (FiveMinuteBar b : bars) {
            var start = b.startTime().withZoneSameInstant(ET).toLocalTime();
            if (start.getHour() == 9 && start.getMinute() == minute) return b;
        }
        return null;
    }

    private DailyBias resolveBias(String symbol, LocalDate barDate) {
        DailyBias cached = biasCache.get(symbol);
        if (cached != null && cached.computedDate().equals(barDate)) {
            return cached;
        }
        DailyBias fresh = computeBias(symbol, barDate);
        biasCache.put(symbol, fresh);
        return fresh;
    }

    private DailyBias computeBias(String symbol, LocalDate barDate) {
        // Fetch smaPeriod + 5 calendar days of buffer (weekends/holidays)
        LocalDate from = barDate.minusDays((long) smaPeriod * 2);
        List<DailyBarSummaryEntity> history =
                dailyRepo.findBySymbolAndTradeDateBetweenOrderByTradeDateAsc(symbol, from, barDate.minusDays(1));

        if (history.size() < smaPeriod) {
            log.debug("[ORR] [{}] insufficient daily history for SMA{}: {} days", symbol, smaPeriod, history.size());
            return DailyBias.unknown(barDate);
        }

        List<DailyBarSummaryEntity> window = history.subList(history.size() - smaPeriod, history.size());
        double sma50 = window.stream()
                .mapToDouble(b -> b.getClosePrice().doubleValue())
                .average()
                .orElse(0);

        double latestClose = history.getLast().getClosePrice().doubleValue();
        return new DailyBias(barDate, sma50, latestClose >= sma50);
    }

    record DailyBias(LocalDate computedDate, double sma50, boolean aboveSma50) {
        static DailyBias unknown(LocalDate date) {
            return new DailyBias(date, 0, false);
        }
    }
}