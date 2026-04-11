package za.co.sfh.stocklistener.payloads;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link AggregateMinuteBar#johnWickType()}.
 *
 * <p>Classification rules (all four must hold simultaneously):
 * <ol>
 *   <li>Dominant wick / range  &ge; 0.60</li>
 *   <li>Body / range           &le; 0.25</li>
 *   <li>Close position         &le; 0.20 from the opposite end</li>
 *   <li>Open position          &le; 0.30 from the opposite end
 *       (guards against large-body candles that incidentally have a long wick)</li>
 * </ol>
 *
 * <p>Real-world test data is sourced from {@code bars-2026-04-08.jsonl} and
 * {@code bars-2026-04-09.jsonl} fixture files.
 */
@DisplayName("JohnWick candle classification")
class JohnWickTypeTest {

    private static final ZonedDateTime T0 = ZonedDateTime.now();
    private static final ZonedDateTime T1 = T0.plusMinutes(1);

    /**
     * Convenience factory — only open / high / low / close matter for
     * {@code johnWickType()}; the remaining fields are set to neutral values.
     */
    private AggregateMinuteBar bar(String sym, double o, double h, double l, double c) {
        double mid = (o + c) / 2.0;
        return new AggregateMinuteBar("AM", sym, 1_000L, 1_000L, o, mid, o, c, h, l, mid, 10, T0, T1);
    }

    // ── BULLISH (hammer): dominant lower wick, open+close near the high ──────

    @Test
    @DisplayName("BMNR — bullish: 91% lower wick, tiny body")
    void bmnr_bullish() {
        // range=0.23  body=0.02(8.7%)  lowerWick=0.21(91.3%)  upperWick=0
        // close_pos=0.087  open_pos=0.0
        assertThat(bar("BMNR", 21.86, 21.86, 21.63, 21.84).johnWickType())
                .isEqualTo(JohnWickType.BULLISH);
    }

    @Test
    @DisplayName("HCAI — bullish: doji with 81% lower wick")
    void hcai_bullish() {
        // range=0.042  body=0.0  lowerWick=0.034(81%)  upperWick=0.008
        // open==close, both near high — classic doji hammer
        assertThat(bar("HCAI", 0.42, 0.4279, 0.3859, 0.42).johnWickType())
                .isEqualTo(JohnWickType.BULLISH);
    }

    @Test
    @DisplayName("CSWC — bullish: open==close==high, 100% lower wick")
    void cswc_bullish() {
        // range=0.23  body=0.0  lowerWick=1.0 — perfect hammer doji
        assertThat(bar("CSWC", 23.7, 23.7, 23.47, 23.7).johnWickType())
                .isEqualTo(JohnWickType.BULLISH);
    }

    @Test
    @DisplayName("UAMY — bullish: close at high, 92% lower wick")
    void uamy_bullish() {
        // range=0.13  body=0.077  lowerWick=0.923
        // open_pos=(8.73-8.72)/0.13=0.077 — both open and close near high
        assertThat(bar("UAMY", 8.72, 8.73, 8.60, 8.73).johnWickType())
                .isEqualTo(JohnWickType.BULLISH);
    }

    @Test
    @DisplayName("VZ — bullish: tiny body near high, 88% lower wick")
    void vz_bullish() {
        // range=0.085  body=0.012  lowerWick=0.882  upperWick=0.0
        assertThat(bar("VZ", 48.74, 48.74, 48.655, 48.73).johnWickType())
                .isEqualTo(JohnWickType.BULLISH);
    }

    @Test
    @DisplayName("GDXU — bullish: large $ range, 95% lower wick")
    void gdxu_bullish() {
        // range=7.81  body=0.018  lowerWick=0.946
        // Verifies classification is ratio-based, not $ absolute
        assertThat(bar("GDXU", 261.53, 261.81, 254.00, 261.39).johnWickType())
                .isEqualTo(JohnWickType.BULLISH);
    }

    @Test
    @DisplayName("HBAN — bullish: open==close==high, 100% lower wick")
    void hban_bullish() {
        // range=0.01  body=0.0  lowerWick=1.0
        assertThat(bar("HBAN", 16.2, 16.2, 16.19, 16.2).johnWickType())
                .isEqualTo(JohnWickType.BULLISH);
    }

    // ── BEARISH (shooting star): dominant upper wick, open+close near the low ─

    @Test
    @DisplayName("CORN — bearish: 92% upper wick, open and close near low")
    void corn_bearish() {
        // range=0.12  body=0.008  upperWick=0.917  lowerWick=0.0
        // open==low, close one tick above → both near the bottom
        assertThat(bar("CORN", 17.88, 18.0, 17.88, 17.89).johnWickType())
                .isEqualTo(JohnWickType.BEARISH);
    }

    @Test
    @DisplayName("IONX — bearish: open==close==low, 100% upper wick")
    void ionx_bearish() {
        // range=0.01  body=0.0  upperWick=1.0 — perfect shooting-star doji
        assertThat(bar("IONX", 22.89, 22.9, 22.89, 22.89).johnWickType())
                .isEqualTo(JohnWickType.BEARISH);
    }

    @Test
    @DisplayName("WTI — bearish: open==close==low, 100% upper wick")
    void wti_bearish() {
        // range=0.01  body=0.0  upperWick=1.0
        assertThat(bar("WTI", 2.94, 2.95, 2.94, 2.94).johnWickType())
                .isEqualTo(JohnWickType.BEARISH);
    }

    // ── NONE: candles that must NOT classify as John Wick ────────────────────

    @Test
    @DisplayName("NONE — regular bullish candle: large body, no dominant wick")
    void regularBullCandle_returnsNone() {
        // range=1.2  body=0.9(75%)  — body far too large
        assertThat(bar("BULL", 10.0, 11.0, 9.8, 10.9).johnWickType())
                .isEqualTo(JohnWickType.NONE);
    }

    @Test
    @DisplayName("NONE — flat bar (high==low): guard clause prevents division by zero")
    void zeroRange_returnsNone() {
        assertThat(bar("FLAT", 5.0, 5.0, 5.0, 5.0).johnWickType())
                .isEqualTo(JohnWickType.NONE);
    }

    @Test
    @DisplayName("NONE — open at midpoint: long lower wick but open_pos fails (new rule)")
    void openAtMidpoint_longLowerWick_failsOpenPosCheck_returnsNone() {
        // Specifically exercises the 4th condition added to tighten classification.
        // h=1.0  l=0.0  o=0.65  c=0.90
        // lowerWick=0.65(65%) ✓  body=0.25(25%) ✓  close_pos=(1-0.9)/1=0.10 ✓
        // open_pos=(1-0.65)/1=0.35 > 0.30 ✗  → must return NONE, not BULLISH
        assertThat(bar("MID", 0.65, 1.0, 0.0, 0.90).johnWickType())
                .isEqualTo(JohnWickType.NONE);
    }
}
