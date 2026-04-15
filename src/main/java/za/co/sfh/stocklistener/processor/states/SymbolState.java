package za.co.sfh.stocklistener.processor.states;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import za.co.sfh.stocklistener.payloads.AggregateMinuteBar;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayDeque;
import java.util.List;

@Slf4j
@Getter
public final class SymbolState {

    private static final int MAX_CANDLES = 1440; // 1 day of 1-minute bars (60 min × 24 h)
    private static final ZoneId ET = ZoneId.of("America/New_York");
    private static final LocalTime MARKET_OPEN = LocalTime.of(9, 30);
    private static final LocalTime MARKET_CLOSE = LocalTime.of(16, 0);

    private static final int EMA_PERIOD = 9;
    private static final double EMA_MULTIPLIER = 2.0 / (EMA_PERIOD + 1); // 0.2

    private final ArrayDeque<AggregateMinuteBar> candles = new ArrayDeque<>();
    // Parallel deque — one VWAP snapshot per candle in the same order, same eviction
    private final ArrayDeque<Double> vwapHistory = new ArrayDeque<>();

    private String symbol;
    private double avgRange;
    private double avgVolume;
    private double preMarketHigh = Double.MIN_VALUE;
    private double preMarketLow  = Double.MAX_VALUE;

    // Session VWAP — reset each trading day
    private double cumulativePV     = 0;
    private double cumulativeVolume = 0;
    private double vwap             = 0;
    private LocalDate sessionDate   = null;

    // 9-period EMA of close price
    private double ema9           = 0;
    private int    totalBars      = 0;   // total bars ever received (not capped)
    private double emaSeedSum     = 0;   // accumulates closes for the SMA seed

    public boolean isOutsideNormalHours(ZonedDateTime barTime) {
        LocalTime lt = barTime.withZoneSameInstant(ET).toLocalTime();
        return lt.isBefore(MARKET_OPEN) || !lt.isBefore(MARKET_CLOSE);
    }

    public void addBar(AggregateMinuteBar bar) {
        if (symbol == null) {
            symbol = bar.symbol();
            log.debug("SymbolState initialised for [{}]", symbol);
        }

        log.debug("[{}] Adding bar [size: {}]", symbol, candles.size());

        if (isOutsideNormalHours(bar.startTimestampMs())) {
            if (bar.high() > preMarketHigh) preMarketHigh = bar.high();
            if (bar.low()  < preMarketLow)  preMarketLow  = bar.low();
        }

        updateVwap(bar);
        updateEma9(bar);

        candles.addLast(bar);
        vwapHistory.addLast(vwap);
        if (candles.size() > MAX_CANDLES) {
            candles.removeFirst();
            vwapHistory.removeFirst();
        }

        updateAverages();
    }

    private void updateVwap(AggregateMinuteBar bar) {
        LocalDate barDate = bar.startTimestampMs().withZoneSameInstant(ET).toLocalDate();
        if (!barDate.equals(sessionDate)) {
            // New session — reset accumulators
            cumulativePV     = 0;
            cumulativeVolume = 0;
            sessionDate      = barDate;
            log.debug("[{}] VWAP reset for new session {}", symbol, barDate);
        }
        double typicalPrice = (bar.high() + bar.low() + bar.close()) / 3.0;
        cumulativePV     += typicalPrice * bar.volume();
        cumulativeVolume += bar.volume();
        vwap = cumulativeVolume > 0 ? cumulativePV / cumulativeVolume : 0;
    }

    private void updateEma9(AggregateMinuteBar bar) {
        totalBars++;
        if (totalBars < EMA_PERIOD) {
            emaSeedSum += bar.close();
        } else if (totalBars == EMA_PERIOD) {
            emaSeedSum += bar.close();
            ema9 = emaSeedSum / EMA_PERIOD; // seed with SMA of first 9 closes
        } else {
            ema9 = bar.close() * EMA_MULTIPLIER + ema9 * (1 - EMA_MULTIPLIER);
        }
    }

    private void updateAverages() {
        if (candles.isEmpty()) return;

        double totalRange = 0;
        double totalVolume = 0;

        for (AggregateMinuteBar b : candles) {
            totalRange += (b.high() - b.low());
            totalVolume += b.volume();
        }

        avgRange = totalRange / candles.size();
        avgVolume = totalVolume / candles.size();
    }

    public List<AggregateMinuteBar> getCandles() {
        return List.copyOf(candles);
    }

    /** VWAP snapshot per candle, aligned 1:1 with {@link #getCandles()}. */
    public List<Double> getVwapHistory() {
        return List.copyOf(vwapHistory);
    }

    public SymbolStateSnapshot toSnapshot() {
        return new SymbolStateSnapshot(
                symbol, avgRange, avgVolume,
                preMarketHigh, preMarketLow,
                cumulativePV, cumulativeVolume, vwap, sessionDate,
                ema9, totalBars, emaSeedSum,
                List.copyOf(candles), List.copyOf(vwapHistory)
        );
    }

    public void restoreFrom(SymbolStateSnapshot s) {
        symbol          = s.symbol();
        avgRange        = s.avgRange();
        avgVolume       = s.avgVolume();
        preMarketHigh   = s.preMarketHigh();
        preMarketLow    = s.preMarketLow();
        cumulativePV    = s.cumulativePV();
        cumulativeVolume = s.cumulativeVolume();
        vwap            = s.vwap();
        sessionDate     = s.sessionDate();
        ema9            = s.ema9();
        totalBars       = s.totalBars();
        emaSeedSum      = s.emaSeedSum();
        candles.clear();
        candles.addAll(s.candles());
        vwapHistory.clear();
        vwapHistory.addAll(s.vwapHistory());
    }
}
