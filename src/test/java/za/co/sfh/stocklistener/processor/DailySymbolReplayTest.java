package za.co.sfh.stocklistener.processor;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import za.co.sfh.stocklistener.payloads.AggregateMinuteBar;
import za.co.sfh.stocklistener.processor.indicators.LinearRegressionIndicator;
import za.co.sfh.stocklistener.processor.indicators.MacdIndicator;
import za.co.sfh.stocklistener.processor.indicators.RocIndicator;
import za.co.sfh.stocklistener.processor.indicators.RsiIndicator;
import za.co.sfh.stocklistener.processor.indicators.VolumeProfileIndicator;
import za.co.sfh.stocklistener.processor.scanners.BreakoutPatternScanner;
import za.co.sfh.stocklistener.processor.scanners.InvertedVeePatternScanner;
import za.co.sfh.stocklistener.processor.scanners.MomentumStrengthScanner;
import za.co.sfh.stocklistener.processor.scanners.UnsharpenPatternScanner;
import za.co.sfh.stocklistener.processor.states.SymbolState;
import za.co.sfh.stocklistener.signals.BreakoutSignal;

import java.lang.reflect.Field;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Post-market replay tool for a single symbol on a specific trading day.
 *
 * ── How to use ────────────────────────────────────────────────────────────────
 *  1. Change TEST_DATE to the date of the fixture file you want to analyse.
 *  2. Change TEST_SYMBOL to the ticker you want to step through.
 *  3. Tune the scanner thresholds in the "Wire up scanners" block if needed.
 *  4. Run the test and read the console output.
 *
 * The test reads src/test/resources/fixtures/bars-{TEST_DATE}.jsonl,
 * filters to TEST_SYMBOL only, and drives SymbolState + every PatternScanner
 * synchronously — no Spring context, no async queue.
 * ─────────────────────────────────────────────────────────────────────────────
 */
class DailySymbolReplayTest {

    // ── Configuration ──────────────────────────────────────────────────────────
    private static final String TEST_DATE   = "2026-05-22";   // yyyy-MM-dd
    private static final String TEST_SYMBOL = "LODE";

    // Filters (match application.yaml)
    private static final double MIN_CLOSE  = 0.10;
    private static final long   MIN_VOLUME = 500;

    // ── Formatting ─────────────────────────────────────────────────────────────
    private static final ZoneId            ET  = ZoneId.of("America/New_York");
    private static final DateTimeFormatter HHmm = DateTimeFormatter.ofPattern("HH:mm");

    // ──────────────────────────────────────────────────────────────────────────

    @Test
    void replaySymbolForDay() throws Exception {
        String fixture = "fixtures/bars-" + TEST_DATE + ".jsonl";
        URL resource = getClass().getClassLoader().getResource(fixture);
        if (resource == null) {
            System.out.println("Skipping DailySymbolReplayTest — fixture not found: " + fixture);
            return;
        }

        // ── ObjectMapper (mirrors JacksonConfig) ───────────────────────────────
        ObjectMapper mapper = JsonMapper.builder()
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .disable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                .enable(DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY)
                .build();

        // ── Collect + filter bars for this symbol ──────────────────────────────
        List<AggregateMinuteBar> bars = new ArrayList<>();
        List<String> lines = Files.readAllLines(Path.of(resource.toURI()));
        for (String line : lines) {
            try {
                JsonNode array = mapper.readTree(line);
                for (JsonNode node : array) {
                    if (!"AM".equals(node.path("ev").asString(null))) continue;
                    if (!TEST_SYMBOL.equals(node.path("sym").asString(null))) continue;
                    AggregateMinuteBar bar = mapper.convertValue(node, AggregateMinuteBar.class);
                    if (bar.close() < MIN_CLOSE || bar.volume() < MIN_VOLUME) continue;
                    bars.add(bar);
                }
            } catch (Exception e) {
                // Some lines in the fixture might be malformed (e.g. status messages appended to data lines)
                // We skip these to allow the replay to continue with valid data.
                System.err.println("Skipping malformed JSON line: " + e.getMessage());
            }
        }

        bars.sort(Comparator.comparing(AggregateMinuteBar::startTimestampMs));

        if (bars.isEmpty()) {
            System.out.printf("No bars found for %s on %s (fixture: %s)%n",
                    TEST_SYMBOL, TEST_DATE, fixture);
            return;
        }

        // ── Wire up scanners ───────────────────────────────────────────────────
        BreakoutPatternScanner breakout = new BreakoutPatternScanner();
        setField(breakout, "stopMultiplier",   0.90);
        setField(breakout, "targetMultiplier", 1.05);
        setField(breakout, "storeSignal",      true);

        UnsharpenPatternScanner unsharpen = new UnsharpenPatternScanner();
        setField(unsharpen, "stopMultiplier",   0.97);
        setField(unsharpen, "targetMultiplier", 1.06);
        setField(unsharpen, "storeSignal",      true);

        InvertedVeePatternScanner invertedVee = new InvertedVeePatternScanner();
        setField(invertedVee, "stopMultiplier",   -1.35);
        setField(invertedVee, "targetMultiplier", 2.0);
        setField(invertedVee, "storeSignal",      false);

        RsiIndicator rsiInd = new RsiIndicator();
        setField(rsiInd, "period",              14);
        setField(rsiInd, "overboughtThreshold", 70.0);
        setField(rsiInd, "oversoldThreshold",   30.0);

        RocIndicator rocInd = new RocIndicator();
        setField(rocInd, "period",    10);
        setField(rocInd, "avgWindow", 10);
        setField(rocInd, "surgeFactor", 1.5);

        MacdIndicator macdInd = new MacdIndicator();
        setField(macdInd, "fastPeriod",   12);
        setField(macdInd, "slowPeriod",   26);
        setField(macdInd, "signalPeriod",  9);

        LinearRegressionIndicator linRegInd = new LinearRegressionIndicator();
        setField(linRegInd, "period",         60);
        setField(linRegInd, "steepThreshold", 0.001);
        setField(linRegInd, "r2Threshold",    0.70);

        VolumeProfileIndicator volInd = new VolumeProfileIndicator();
        setField(volInd, "shortPeriod",        60);
        setField(volInd, "longPeriod",         390);
        setField(volInd, "surgeThreshold",     1.5);
        setField(volInd, "convictionThreshold", 2.0);

        MomentumStrengthScanner momentumStrength = new MomentumStrengthScanner(
                rsiInd, rocInd, macdInd, linRegInd, volInd, null);
        setField(momentumStrength, "stopMultiplier",       0.97);
        setField(momentumStrength, "targetMultiplier",     1.06);
        setField(momentumStrength, "storeSignal",          true);
        setField(momentumStrength, "highScoreThreshold",   7);
        setField(momentumStrength, "veryHighScoreThreshold", 10);
        setField(momentumStrength, "rsiStrongThreshold",   60.0);

        List<PatternScanner> scanners = List.of(breakout, unsharpen, invertedVee, momentumStrength);

        // ── State ──────────────────────────────────────────────────────────────
        SymbolState state = new SymbolState();

        // ── Captured signals ───────────────────────────────────────────────────
        record DetectedSignal(String time, int barIndex, AggregateMinuteBar bar, BreakoutSignal signal) {}
        List<DetectedSignal> detected = new ArrayList<>();

        // ── Header ────────────────────────────────────────────────────────────
        System.out.println();
        System.out.printf("══════════════════════════════════════════════════════════════════════════════════%n");
        System.out.printf("  Replay  %-6s   %s   (%d bars after filter)%n", TEST_SYMBOL, TEST_DATE, bars.size());
        System.out.printf("══════════════════════════════════════════════════════════════════════════════════%n");
        System.out.printf("%-5s  %-7s %-7s %-7s %-7s  %-8s  %-7s %-7s  %-7s %-7s  %-16s%n",
                "Time", "Open", "High", "Low", "Close", "Volume",
                "EMA9", "VWAP", "AvgRange", "AvgVol", "Signal");
        System.out.printf("──────  ──────  ──────  ──────  ──────   ────────  ──────  ──────   ──────  ──────   ────────────────%n");

        // ── Replay loop ────────────────────────────────────────────────────────
        for (int i = 0; i < bars.size(); i++) {
            AggregateMinuteBar bar = bars.get(i);
            state.addBar(bar);

            String time = bar.startTimestampMs().withZoneSameInstant(ET).format(HHmm);
            final int barIndex = i;

            List<String> signalLabels = new ArrayList<>();
            for (PatternScanner scanner : scanners) {
                Optional<BreakoutSignal> result = scanner.scan(bar, state);
                result.ifPresent(s -> {
                    signalLabels.add(s.pattern().name());
                    detected.add(new DetectedSignal(time, barIndex, bar, s));
                });
            }

            System.out.printf("%-5s  %6.2f  %6.2f  %6.2f  %6.2f   %8d  %6.2f  %6.2f   %6.4f  %8.0f  %s%n",
                    time,
                    bar.open(), bar.high(), bar.low(), bar.close(),
                    bar.volume(),
                    state.getEma9(),
                    state.getVwap(),
                    state.getAvgRange(),
                    state.getAvgVolume(),
                    String.join(", ", signalLabels));
        }

        // ── Signal summary ─────────────────────────────────────────────────────
        System.out.printf("%n══════════════════════════════════════════════════════════════════════════════════════════════════════%n");
        System.out.printf("  Signal Summary  —  %d signal(s) detected%n", detected.size());
        System.out.printf("══════════════════════════════════════════════════════════════════════════════════════════════════════%n");

        if (detected.isEmpty()) {
            System.out.println("  (none)");
        } else {
            System.out.printf("  %-5s  %-16s  %-7s  %-7s  %-7s  %-5s  %-12s %-12s %-12s  %s%n",
                    "Time", "Pattern", "Entry", "Stop", "Target", "R", "+5m", "+10m", "+30m", "Notes");
            System.out.printf("  ─────  ────────────────  ──────  ──────  ──────  ─────  ─────────── ─────────── ───────────  ─────%n");
            for (DetectedSignal ds : detected) {
                BreakoutSignal sig   = ds.signal();
                double entry  = sig.entry();
                double stop   = sig.stop();
                double target = sig.target();
                double risk   = entry - stop;
                double rMult  = risk > 0 ? (target - entry) / risk : Double.NaN;

                System.out.printf("  %-5s  %-16s  %6.2f  %6.2f  %6.2f  %4.1fR  %-11s %-11s %-11s  %s%n",
                        ds.time(),
                        sig.pattern().name(),
                        entry, stop, target,
                        Double.isNaN(rMult) ? 0.0 : rMult,
                        outcome(bars, ds.barIndex(), entry, stop,  5),
                        outcome(bars, ds.barIndex(), entry, stop, 10),
                        outcome(bars, ds.barIndex(), entry, stop, 30),
                        sig.notes());
            }
        }

        System.out.printf("%n  Final state: EMA9=%.4f  VWAP=%.4f  avgRange=%.4f  avgVolume=%.0f  candles=%d%n",
                state.getEma9(), state.getVwap(), state.getAvgRange(), state.getAvgVolume(),
                state.getCandles().size());
        System.out.printf("══════════════════════════════════════════════════════════════════════════════════════════════════════%n%n");
    }

    /**
     * Looks up the close {@code barsAhead} bars after the signal bar and returns a formatted
     * outcome string: e.g. "+2.34% W" (close above entry), "-1.10% L" (close below stop),
     * "+0.45% ~" (between stop and entry — trade still open / undecided), or "-" if the bar
     * is beyond the end of the day's data.
     */
    private String outcome(List<AggregateMinuteBar> bars, int signalIndex, double entry, double stop, int barsAhead) {
        int targetIndex = signalIndex + barsAhead;
        if (targetIndex >= bars.size()) return "-";

        double futureClose = bars.get(targetIndex).close();
        double pct = (futureClose - entry) / entry * 100.0;
        String label = futureClose >= entry ? "W" : (futureClose <= stop ? "L" : "~");
        return String.format("%+.2f%% %s", pct, label);
    }

    private void setField(Object target, String name, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }
}
