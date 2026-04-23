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

    private final DailyBarSummaryRepository dailyBarSummaryRepository;

    public void scanForPossibleFireTraders() {

        int lookbackDays = 30;
        LocalDate toDate = LocalDate.now();
        LocalDate fromDate = toDate.minusDays(lookbackDays);

        List<String> symbolsList = dailyBarSummaryRepository.findDistinctSymbolBetweenDates(fromDate, toDate);
        log.debug("Retrieved symbols to process: [listSize: {}]", symbolsList.size());

        for (String symbol : symbolsList) {
            List<DailyBarSummaryEntity> bars = fetchDailyBarsForSymbol(symbol, lookbackDays);
            boolean fired = hadPositiveMove(symbol, bars, 30.0, 5);
            if (fired) {
                log.info("FireTrade candidate detected: symbol={}", symbol);
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
     * Returns true if, within any rolling window of {@code windowSize} trading days,
     * the high price of any day in that window exceeded the open price of the window's
     * first day by at least {@code thresholdPct} percent.
     *
     * @param bars         daily bars sorted ascending by trade date
     * @param thresholdPct required percentage gain (e.g. 30.0 for 30 %)
     * @param windowSize   rolling window size in trading days
     */
    private boolean hadPositiveMove(String symbol,
                                    List<DailyBarSummaryEntity> bars,
                                    double thresholdPct,
                                    int windowSize) {

        log.debug("Starting to search for a hadPositiveMove [symbol: {}; barsSize: {}; thresholdPct: {}; windowSize: {}]", symbol, bars.size(), thresholdPct, windowSize);

        if (bars == null || bars.size() < 2) {
            return false;
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
                    return true;
                }
            }
        }
        return false;
    }
}
