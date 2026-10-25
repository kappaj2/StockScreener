package za.co.sfh.stocklistener.signals;

public record BreakoutSignal(
        String id,
        String symbol,
        PatternType pattern,
        double entry,
        double stop,
        double target,
        int    confidence,
        String risk,
        String notes,
        long   timestamp,
        double preMarketHigh,
        double preMarketLow,
        String news,
        boolean highWatch,
        double rvol
) {
    public String displayCode() {
        return highWatch ? "HW_" + pattern.getCode() : pattern.getCode();
    }

    /** Convenience factory for signals that do not have an RVOL value (1-min pattern scanners). */
    public static BreakoutSignal withoutRvol(String id, String symbol, PatternType pattern,
                                             double entry, double stop, double target,
                                             int confidence, String risk, String notes,
                                             long timestamp, double preMarketHigh,
                                             double preMarketLow, String news, boolean highWatch) {
        return new BreakoutSignal(id, symbol, pattern, entry, stop, target, confidence, risk,
                notes, timestamp, preMarketHigh, preMarketLow, news, highWatch, Double.NaN);
    }

    public boolean hasRvol() {
        return !Double.isNaN(rvol);
    }
}