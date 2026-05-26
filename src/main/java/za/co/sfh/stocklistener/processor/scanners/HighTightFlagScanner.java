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
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
@RequiredArgsConstructor
public class HighTightFlagScanner implements PatternScanner {

    private static final ZoneId ET = ZoneId.of("America/New_York");

    @Value("${patterns.htf.target:1.15}")
    private double targetMultiplier;

    @Value("${patterns.htf.storeSignal:false}")
    private boolean storeSignal;

    @Value("${patterns.htf.min-advance-pct:30.0}")
    private double minAdvancePct;

    @Value("${patterns.htf.max-pullback-pct:25.0}")
    private double maxPullbackPct;

    @Value("${patterns.htf.lookback-days:60}")
    private int lookbackDays;

    @Value("${patterns.htf.ma-period:20}")
    private int maPeriod;

    @Value("${patterns.htf.min-consolidation-days:5}")
    private int minConsolidationDays;

    @Value("${patterns.htf.volume-multiplier:1.5}")
    private double volumeMultiplier;

    private final DailyBarSummaryRepository dailyRepo;

    // Per-symbol cache; invalidated when the trading date changes
    private final ConcurrentHashMap<String, HtfDailyState> htfCache = new ConcurrentHashMap<>();

    @Override
    public boolean shouldStore() {
        return storeSignal;
    }

    @Override
    public Optional<BreakoutSignal> scan(AggregateMinuteBar bar, SymbolState state) {
        HtfDailyState htf = resolveHtfState(bar.symbol());

        if (!htf.valid()) {
            log.debug("[HTF] {} not in setup: {}", bar.symbol(), htf.reason());
            return Optional.empty();
        }

        // Price must clear the flag's consolidation high
        if (bar.close() <= htf.consolidationHigh()) return Optional.empty();

        // Volume must show conviction vs. the session's average minute-bar volume
        if (bar.volume() < volumeMultiplier * state.getAvgVolume()) return Optional.empty();

        log.info("[HTF] Breakout [symbol={}; close={}; flagHigh={}; flagLow={}; volume={}]",
                bar.symbol(), bar.close(), htf.consolidationHigh(), htf.consolidationLow(), bar.volume());

        return Optional.of(new BreakoutSignal(
                UUID.randomUUID().toString(),
                bar.symbol(),
                PatternType.HIGH_TIGHT_FLAG,
                bar.close(),
                htf.consolidationLow(),       // stop = flag low (Kullamagi rule)
                bar.close() * targetMultiplier,
                90,
                "1% account risk / flag-low stop",
                "HTF breakout above flag high",
                bar.endTimestampMs().toInstant().toEpochMilli(),
                state.getPreMarketHigh(),
                state.getPreMarketLow(),
                null,
                false
        ));
    }

    private HtfDailyState resolveHtfState(String symbol) {
        LocalDate today = LocalDate.now(ET);
        HtfDailyState cached = htfCache.get(symbol);
        if (cached != null && cached.computedDate().equals(today)) {
            return cached;
        }
        HtfDailyState fresh = computeHtfState(symbol, today);
        htfCache.put(symbol, fresh);
        return fresh;
    }

    private HtfDailyState computeHtfState(String symbol, LocalDate today) {
        // Fetch enough history for both the MA window and the full lookback
        LocalDate fromDate = today.minusDays((long) lookbackDays + maPeriod);
        // Exclude today — its daily bar isn't closed yet
        List<DailyBarSummaryEntity> bars =
                dailyRepo.findBySymbolAndTradeDateBetweenOrderByTradeDateAsc(symbol, fromDate, today.minusDays(1));

        if (bars.size() < maPeriod + minConsolidationDays) {
            return HtfDailyState.invalid(today, "insufficient history: " + bars.size() + " days - minimum "+maPeriod);
        }

        // --- Pole: highest intraday high across the entire lookback ---
        int poleTopIdx = 0;
        double poleHigh = 0;
        for (int i = 0; i < bars.size(); i++) {
            double h = bars.get(i).getHighPrice().doubleValue();
            if (h > poleHigh) {
                poleHigh = h;
                poleTopIdx = i;
            }
        }

        int daysInFlag = bars.size() - 1 - poleTopIdx;
        if (daysInFlag < minConsolidationDays) {
            return HtfDailyState.invalid(today, "pole too recent: only " + daysInFlag + " flag days");
        }

        // --- Advance check: 30%+ over the 20 trading days leading to the pole top ---
        int advanceStartIdx = Math.max(0, poleTopIdx - 20);
        double advanceStartClose = bars.get(advanceStartIdx).getClosePrice().doubleValue();
        double advancePct = (poleHigh - advanceStartClose) / advanceStartClose * 100.0;
        if (advancePct < minAdvancePct) {
            return HtfDailyState.invalid(today,
                    String.format("advance %.1f%% below %.1f%% threshold", advancePct, minAdvancePct));
        }

        // --- Flag: all days after the pole top ---
        List<DailyBarSummaryEntity> flag = bars.subList(poleTopIdx + 1, bars.size());

        // Pullback from pole high must not exceed maxPullbackPct
        double flagLow = flag.stream()
                .mapToDouble(b -> b.getClosePrice().doubleValue())
                .min().orElse(poleHigh);
        double pullbackPct = (poleHigh - flagLow) / poleHigh * 100.0;
        if (pullbackPct > maxPullbackPct) {
            return HtfDailyState.invalid(today,
                    String.format("pullback %.1f%% exceeds %.1f%% max", pullbackPct, maxPullbackPct));
        }

        // 20-day SMA computed from the most recent maPeriod daily closes
        List<DailyBarSummaryEntity> maWindow = bars.subList(bars.size() - maPeriod, bars.size());
        double ma20 = maWindow.stream()
                .mapToDouble(b -> b.getClosePrice().doubleValue())
                .average().orElse(0);

        // Most recent close must be above the 20-day MA (still "surfing" it)
        double latestClose = bars.getLast().getClosePrice().doubleValue();
        if (latestClose < ma20) {
            return HtfDailyState.invalid(today,
                    String.format("latest close %.2f below 20-day MA %.2f", latestClose, ma20));
        }

        // Flag is broken if any day closed below the 20-day MA on heavy volume
        double avgVolume = bars.stream().mapToLong(DailyBarSummaryEntity::getVolume).average().orElse(1);
        for (DailyBarSummaryEntity day : flag) {
            if (day.getClosePrice().doubleValue() < ma20 && day.getVolume() > 1.5 * avgVolume) {
                return HtfDailyState.invalid(today,
                        "flag broken: " + day.getTradeDate() + " closed below 20-day MA on heavy volume");
            }
        }

        // Breakout level = highest intraday high recorded during the flag
        double flagHigh = flag.stream()
                .mapToDouble(b -> b.getHighPrice().doubleValue())
                .max().orElse(poleHigh);

        log.debug("[HTF] {} setup valid: poleHigh={}, advance={}%, pullback={}%, flagHigh={}, flagLow={}, ma20={}",
                symbol, poleHigh,
                String.format("%.1f", advancePct), String.format("%.1f", pullbackPct),
                flagHigh, flagLow, String.format("%.2f", ma20));

        return new HtfDailyState(today, true, flagHigh, flagLow, "valid");
    }

    record HtfDailyState(
            LocalDate computedDate,
            boolean valid,
            double consolidationHigh,
            double consolidationLow,
            String reason
    ) {
        static HtfDailyState invalid(LocalDate date, String reason) {
            return new HtfDailyState(date, false, 0, 0, reason);
        }
    }
}