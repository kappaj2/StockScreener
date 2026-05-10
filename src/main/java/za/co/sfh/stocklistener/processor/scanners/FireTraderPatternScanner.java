package za.co.sfh.stocklistener.processor.scanners;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import za.co.sfh.stocklistener.persistence.entities.DailyBarSummaryEntity;
import za.co.sfh.stocklistener.persistence.repositories.DailyBarSummaryRepository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class FireTraderPatternScanner {

    private static final int FLAG_MAX_BARS = 10;

    private final DailyBarSummaryRepository dailyBarSummaryRepository;

    /**
     * Result of a positive-move search.
     *
     * @param found        true when the threshold move was detected
     * @param highDate     trade date of the bar that produced the peak high
     * @param highPrice    the peak high price
     * @param highBarIndex index into the bars list of the peak bar
     */
    record PositiveMoveResult(boolean found, LocalDate highDate, BigDecimal highPrice, int highBarIndex) {
        static PositiveMoveResult notFound() {
            return new PositiveMoveResult(false, null, null, -1);
        }
    }

    /**
     * Result of a tight-ranging-flag check.
     *
     * @param found          true when a tightening flag was detected
     * @param rangeStartDate first bar of the flag window
     * @param rangeEndDate   last bar of the flag window
     */
    record TightRangingResult(boolean found, LocalDate rangeStartDate, LocalDate rangeEndDate) {
        static TightRangingResult notFound() {
            return new TightRangingResult(false, null, null);
        }
    }

    public void scanForPossibleFireTraders() {

        int lookbackDays = 30;
        LocalDate toDate = LocalDate.now();
        LocalDate fromDate = toDate.minusDays(lookbackDays);

        List<String> symbolsList = dailyBarSummaryRepository.findDistinctSymbolBetweenDates(fromDate, toDate);
        log.debug("Retrieved symbols to process: [listSize: {}]", symbolsList.size());

        for (String symbol : symbolsList) {
            List<DailyBarSummaryEntity> bars = fetchDailyBarsForSymbol(symbol, lookbackDays);
            PositiveMoveResult moveResult = hadPositiveMove(symbol, bars, 30.0, 5);
            if (moveResult.found()) {
                log.info("FireTrade positive move detected: symbol={} highDate={} highPrice={}",
                        symbol, moveResult.highDate(), moveResult.highPrice());
                TightRangingResult flagResult = hasTightRangingAfterHighs(bars, moveResult, 3, 15.0);
                if (flagResult.found()) {
                    log.info("FireTrade candidate with tightening flag: symbol={} flagStart={} flagEnd={}",
                            symbol, flagResult.rangeStartDate(), flagResult.rangeEndDate());
                }
            }
        }
    }

    private List<DailyBarSummaryEntity> fetchDailyBarsForSymbol(String symbol, int days) {
        LocalDate toDate = LocalDate.now();
        LocalDate fromDate = toDate.minusDays(days);
        return dailyBarSummaryRepository
                .findBySymbolAndTradeDateBetweenOrderByTradeDateAsc(symbol, fromDate, toDate);
    }

    /**
     * Returns a {@link PositiveMoveResult} describing whether, within any rolling window of
     * {@code windowSize} trading days, the high of any bar exceeded the open of the window's
     * first bar by at least {@code thresholdPct} percent. When multiple windows qualify, the
     * first (earliest) match is returned so the flag check starts from the correct peak.
     *
     * @param bars         daily bars sorted ascending by trade date
     * @param thresholdPct required percentage gain (e.g. 30.0 for 30 %)
     * @param windowSize   rolling window size in trading days
     */
    private PositiveMoveResult hadPositiveMove(String symbol,
                                               List<DailyBarSummaryEntity> bars,
                                               double thresholdPct,
                                               int windowSize) {

        log.debug("Starting to search for a hadPositiveMove [symbol: {}; barsSize: {}; thresholdPct: {}; windowSize: {}]",
                symbol, bars.size(), thresholdPct, windowSize);

        if (bars.size() < 2) {
            return PositiveMoveResult.notFound();
        }

        BigDecimal multiplier = BigDecimal.valueOf(1.0 + thresholdPct / 100.0);

        for (int i = 0; i < bars.size(); i++) {
            BigDecimal windowOpen = bars.get(i).getOpenPrice();
            if (windowOpen == null || windowOpen.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            BigDecimal target = windowOpen.multiply(multiplier);

            int windowEnd = Math.min(i + windowSize, bars.size());
            for (int j = i; j < windowEnd; j++) {
                BigDecimal high = bars.get(j).getHighPrice();
                if (high != null && high.compareTo(target) >= 0) {
                    log.debug("Positive move found: symbol={} openDate={} highDate={} open={} high={} target={}",
                            bars.get(i).getSymbol(),
                            bars.get(i).getTradeDate(),
                            bars.get(j).getTradeDate(),
                            windowOpen, high, target);
                    return new PositiveMoveResult(true, bars.get(j).getTradeDate(), high, j);
                }
            }
        }
        return PositiveMoveResult.notFound();
    }

    /**
     * Detects a tightening flag (pennant) in the bars that follow the peak identified by
     * {@code moveResult}. A tightening flag requires:
     * <ol>
     *   <li>At least {@code minBars} bars after the peak.</li>
     *   <li>Every flag bar's high stays below the peak high and its low stays within
     *       {@code proximityPct}% of the peak high (i.e. price consolidates just below
     *       the high rather than selling off hard).</li>
     *   <li>Bar ranges (high − low) are tightening from the older end to the newer end:
     *       the average range of the second half of the flag window is smaller than the
     *       average range of the first half.</li>
     * </ol>
     *
     * @param bars           daily bars sorted ascending by trade date
     * @param moveResult     result from {@link #hadPositiveMove} — supplies the peak bar index
     * @param minBars        minimum number of flag bars required (must be ≥ 2)
     * @param proximityPct   how far (in %) the flag lows may drop below the peak high
     *                       before the pattern is invalidated (e.g. 15.0 for 15 %)
     */
    private TightRangingResult hasTightRangingAfterHighs(List<DailyBarSummaryEntity> bars,
                                                         PositiveMoveResult moveResult,
                                                         int minBars,
                                                         double proximityPct) {

        int startIndex = moveResult.highBarIndex() + 1;
        if (startIndex >= bars.size()) {
            log.debug("hasTightRangingAfterHighs: no bars after peak, skipping");
            return TightRangingResult.notFound();
        }

        // Cap the flag window so we don't look too far from the peak.
        int endIndex = Math.min(startIndex + FLAG_MAX_BARS, bars.size());
        List<DailyBarSummaryEntity> flagBars = bars.subList(startIndex, endIndex);

        if (flagBars.size() < minBars) {
            log.debug("hasTightRangingAfterHighs: only {} flag bars available, need {}", flagBars.size(), minBars);
            return TightRangingResult.notFound();
        }

        BigDecimal peakHigh = moveResult.highPrice();
        BigDecimal proximityFloor = peakHigh.multiply(BigDecimal.valueOf(1.0 - proximityPct / 100.0));

        // All flag bars must stay below the peak high and within proximity of it.
        for (DailyBarSummaryEntity bar : flagBars) {
            BigDecimal barHigh = bar.getHighPrice();
            BigDecimal barLow = bar.getLowPrice();
            if (barHigh == null || barLow == null) {
                return TightRangingResult.notFound();
            }
            if (barHigh.compareTo(peakHigh) > 0) {
                log.debug("hasTightRangingAfterHighs: bar on {} exceeded peak high, not a flag", bar.getTradeDate());
                return TightRangingResult.notFound();
            }
            if (barLow.compareTo(proximityFloor) < 0) {
                log.debug("hasTightRangingAfterHighs: bar on {} low dropped below proximity floor, not a flag", bar.getTradeDate());
                return TightRangingResult.notFound();
            }
        }

        // Tightening check: average range in the second half must be smaller than in the first half.
        int mid = flagBars.size() / 2;
        double firstHalfAvgRange = flagBars.subList(0, mid).stream()
                .mapToDouble(b -> b.getHighPrice().subtract(b.getLowPrice()).doubleValue())
                .average()
                .orElse(0.0);
        double secondHalfAvgRange = flagBars.subList(mid, flagBars.size()).stream()
                .mapToDouble(b -> b.getHighPrice().subtract(b.getLowPrice()).doubleValue())
                .average()
                .orElse(0.0);

        if (secondHalfAvgRange >= firstHalfAvgRange) {
            log.debug("hasTightRangingAfterHighs: ranges not tightening (firstHalfAvg={} secondHalfAvg={})",
                    firstHalfAvgRange, secondHalfAvgRange);
            return TightRangingResult.notFound();
        }

        log.debug("hasTightRangingAfterHighs: tightening flag confirmed (firstHalfAvg={} secondHalfAvg={} peakHigh={})",
                firstHalfAvgRange, secondHalfAvgRange, peakHigh);
        return new TightRangingResult(true,
                flagBars.getFirst().getTradeDate(),
                flagBars.getLast().getTradeDate());
    }
}
