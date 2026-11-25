package za.co.sfh.stocklistener.processor.indicators;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import za.co.sfh.stocklistener.payloads.FiveMinuteBar;
import za.co.sfh.stocklistener.persistence.repositories.FiveMinuteBarRepository;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * Relative Volume (RVOL) indicator for 5-minute bars.
 *
 * <p>Compares the current bar's volume against the volume of the matching time slot
 * on the most recent prior trading day (Mon-Fri, excluding today).
 *
 * <ul>
 *   <li>RVOL = 1.0 → same volume as the equivalent bar yesterday</li>
 *   <li>RVOL &gt; 1.0 → higher than usual (e.g. 1.5 = 50 % more)</li>
 *   <li>RVOL &lt; 1.0 → lower than usual (e.g. 0.5 = half)</li>
 * </ul>
 *
 * <p>Returns {@link Double#NaN} when no prior-day bar is available for the time slot
 * (first session after a holiday, missing data, etc.).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RelativeVolumeIndicator {

    private static final ZoneId ET = ZoneId.of("America/New_York");

    private final FiveMinuteBarRepository repository;

    /**
     * Compute the RVOL ratio for the given bar.
     *
     * @param bar the just-completed 5-minute bar
     * @return RVOL ratio, or {@link Double#NaN} when no prior-day reference exists
     */
    public double compute(FiveMinuteBar bar) {
        Instant prevDaySlotStart = previousTradingDaySlot(bar.startTime());
        return repository.findBySymbolAndBarStart(bar.symbol(), prevDaySlotStart)
                .filter(prev -> prev.getVolume() != null && prev.getVolume() > 0)
                .map(prev -> (double) bar.volume() / prev.getVolume())
                .orElseGet(() -> {
                    log.debug("[{}] RVOL: no prior-day bar found for slot {}", bar.symbol(), prevDaySlotStart);
                    return Double.NaN;
                });
    }

    /**
     * Given a bar's start time, compute the {@link Instant} of the same time slot
     * on the nearest prior trading day (skips Saturday → Friday, Sunday → Friday,
     * Monday → Friday; any other day → day-1).
     */
    private Instant previousTradingDaySlot(ZonedDateTime barStart) {
        ZonedDateTime et = barStart.withZoneSameInstant(ET);
        ZonedDateTime prev = et.minusDays(1);
        if (prev.getDayOfWeek() == DayOfWeek.SUNDAY) {
            prev = prev.minusDays(2);   // → Friday
        } else if (prev.getDayOfWeek() == DayOfWeek.SATURDAY) {
            prev = prev.minusDays(1);   // → Friday
        }
        return prev.toInstant();
    }
}