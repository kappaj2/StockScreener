package za.co.sfh.stocklistener.processor.scanners.scalping.rumers;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import za.co.sfh.stocklistener.payloads.FiveMinuteBar;
import za.co.sfh.stocklistener.payloads.JohnWickType;
import za.co.sfh.stocklistener.processor.scanners.scalping.ScalpingPatternScanner;
import za.co.sfh.stocklistener.processor.states.FiveMinuteState;
import za.co.sfh.stocklistener.signals.BreakoutSignal;
import za.co.sfh.stocklistener.signals.PatternType;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Detects a 3-bar bullish reversal scalping setup:
 *
 * <pre>
 *   Bar -2 (Setup)  : Red/bearish close-down bar — sellers in control
 *   Bar -1 (Signal) : Bullish John Wick — long lower wick, small body, hammer
 *                     Price rejected the lows and closed near the top of its range
 *   Bar  0 (Entry)  : Current bar opens and is green — confirms the reversal
 *                     Enter at the close of this bar
 * </pre>
 *
 * The stop is placed below the John Wick bar's low.
 * The target is entry + (entry - stop) × rewardRatio.
 *
 * @see <a href="file:src/main/resources/images/RumorsJohnWick.png">RumorsJohnWick.png</a>
 */
@Slf4j
@Component
public class JohnWickReversalScanner implements ScalpingPatternScanner {

    @Value("${scalping.rumers.john-wick-reversal.enabled:true}")
    private boolean enabled;

    @Value("${scalping.rumers.john-wick-reversal.reward-ratio:2.0}")
    private double rewardRatio;

    // Setup bar must close down by at least this % (open → close) to qualify as a real sell-off
    @Value("${scalping.rumers.john-wick-reversal.min-setup-drop-pct:0.1}")
    private double minSetupDropPct;

    // John Wick bar: lower wick must be at least this multiple of the bar's body
    @Value("${scalping.rumers.john-wick-reversal.min-wick-body-ratio:2.0}")
    private double minWickBodyRatio;

    // Current bar must be green — this is the entry confirmation
    // Optionally require volume on the entry bar to exceed the John Wick bar's volume
    @Value("${scalping.rumers.john-wick-reversal.require-volume-confirmation:false}")
    private boolean requireVolumeConfirmation;

    // All three bar lows (setup, wick, entry) must be within this % of the wick bar's low
    @Value("${scalping.rumers.john-wick-reversal.max-low-divergence-pct:1.5}")
    private double maxLowDivergencePct;

    @Override
    public Optional<BreakoutSignal> scan(FiveMinuteBar bar, FiveMinuteState state) {
        if (!enabled) return Optional.empty();

        List<FiveMinuteBar> bars = state.getBars();
        if (bars.size() < 3) return Optional.empty();

        int n = bars.size();
        FiveMinuteBar setupBar    = bars.get(n - 3);  // Bar -2: the red sell-off
        FiveMinuteBar wickBar     = bars.get(n - 2);  // Bar -1: John Wick hammer
        FiveMinuteBar entryBar    = bars.get(n - 1);  // Bar  0: current green confirmation
        // Note: bar param == entryBar (last bar added), used for signal construction

        // 1. Setup bar must be red and drop meaningfully
        if (!setupBar.isRed()) {
            log.debug("[JWR] [{}] setup bar not red — skipping", bar.symbol());
            return Optional.empty();
        }
        if (setupBar.open() <= 0) return Optional.empty();
        double setupDropPct = (setupBar.open() - setupBar.close()) / setupBar.open() * 100;
        if (setupDropPct < minSetupDropPct) {
            log.debug("[JWR] [{}] setup bar drop {}% < min {}%", bar.symbol(), setupDropPct, minSetupDropPct);
            return Optional.empty();
        }

        // 2. Wick bar must be a bullish John Wick (dominant lower wick)
        if (wickBar.johnWickType() != JohnWickType.BULLISH) {
            log.debug("[JWR] [{}] wick bar is not a bullish John Wick (type={})", bar.symbol(), wickBar.johnWickType());
            return Optional.empty();
        }

        // 2a. Extra guard: lower wick must be at least minWickBodyRatio × body
        //     (the JohnWick classification already enforces wick ≥ 60% of range,
        //     but this ensures the wick is meaningfully larger than the body)
        double wickBody = wickBar.body();
        if (wickBody > 0 && wickBar.lowerWick() < minWickBodyRatio * wickBody) {
            log.debug("[JWR] [{}] lower wick not dominant enough vs body", bar.symbol());
            return Optional.empty();
        }

        // 3. Entry bar (current bar) must be green — reversal confirmation
        if (!entryBar.isGreen()) {
            log.debug("[JWR] [{}] entry bar not green — reversal not confirmed", bar.symbol());
            return Optional.empty();
        }

        // 4. Optional volume confirmation: entry bar volume >= wick bar volume
        if (requireVolumeConfirmation && entryBar.volume() < wickBar.volume()) {
            log.debug("[JWR] [{}] entry bar volume {} < wick bar volume {} — skipping",
                    bar.symbol(), entryBar.volume(), wickBar.volume());
            return Optional.empty();
        }

        // 5. All three lows must cluster within maxLowDivergencePct% of the wick bar's low —
        //    confirms a genuine support level rather than an isolated spike
        double wickLow    = wickBar.low();
        double tolerance  = wickLow * (maxLowDivergencePct / 100.0);
        double setupDelta = Math.abs(setupBar.low() - wickLow);
        double entryDelta = Math.abs(entryBar.low() - wickLow);
        if (setupDelta > tolerance || entryDelta > tolerance) {
            log.debug("[JWR] [{}] lows not clustered — setupLow={} wickLow={} entryLow={} tolerance={}",
                    bar.symbol(), setupBar.low(), wickLow, entryBar.low(), String.format("%.4f", tolerance));
            return Optional.empty();
        }

        double entry = entryBar.close();
        double stop  = wickBar.low();   // below the wick low — that's where the reversal fails
        if (stop >= entry) return Optional.empty();  // degenerate guard

        double risk   = entry - stop;
        double target = entry + risk * rewardRatio;

        log.info("[JWR] [{}] John Wick Reversal detected — entry={} stop={} target={} setupDrop={}% wickLow={}",
                bar.symbol(), entry, stop, String.format("%.4f", target),
                String.format("%.2f", setupDropPct), wickBar.low());

        return Optional.of(new BreakoutSignal(
                UUID.randomUUID().toString(),
                bar.symbol(),
                PatternType.JOHN_WICK_REVERSAL,
                entry,
                stop,
                target,
                80,
                "low",
                String.format("JW reversal: setup drop=%.2f%% wick-low=%.4f entry=%.4f R:R=1:%.1f",
                        setupDropPct, wickBar.low(), entry, rewardRatio),
                entryBar.endTime().toInstant().toEpochMilli(),
                0,
                0,
                null,
                false
        ));
    }
}
