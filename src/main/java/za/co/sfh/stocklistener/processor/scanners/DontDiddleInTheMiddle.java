package za.co.sfh.stocklistener.processor.scanners;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import za.co.sfh.stocklistener.processor.states.SymbolState;

/**
 * Final gate before a signal is stored.
 * <p>
 * Calculates the middle {@code patterns.dont-diddle.percentage}% of the previous
 * day's full range and suppresses any signal whose entry price falls inside that band.
 * A price stuck in the middle of yesterday's range signals market indecision — not a
 * compelling setup.
 * <p>
 * Returns {@code true} (suppress) when the price is inside the zone.
 * Returns {@code false} (allow) when the price is outside the zone, or when the
 * previous day's range is not yet available (first session ever seen for this symbol).
 */
@Slf4j
@Component
public class DontDiddleInTheMiddle {

    @Value("${patterns.dont-diddle.percentage:30}")
    private double diddlePercentage;

    /**
     * @param price the signal's entry price to evaluate
     * @param state current symbol state containing the previous day's high/low
     * @return {@code true} if the price is inside the "do-not-trade" middle zone
     */
    public boolean isInMiddle(double price, SymbolState state) {
        double prevHigh = state.getPrevDayHigh();
        double prevLow  = state.getPrevDayLow();

        if (prevHigh <= 0 || prevLow <= 0 || prevHigh <= prevLow) {
            log.debug("[{}] DontDiddleInTheMiddle — previous day range not available, passing through", state.getSymbol());
            return false;
        }

        double range    = prevHigh - prevLow;
        double midpoint = (prevHigh + prevLow) / 2.0;
        double halfZone = range * (diddlePercentage / 100.0) / 2.0;
        double zoneHigh = midpoint + halfZone;
        double zoneLow  = midpoint - halfZone;

        boolean inMiddle = price >= zoneLow && price <= zoneHigh;

        if (inMiddle) {
            log.debug("[{}] DontDiddleInTheMiddle — suppressing signal: price={} is inside middle zone [{}, {}] " +
                            "(prevHigh={}, prevLow={}, diddlePct={}%)",
                    state.getSymbol(), price, zoneLow, zoneHigh, prevHigh, prevLow, diddlePercentage);
        }

        return inMiddle;
    }
}
