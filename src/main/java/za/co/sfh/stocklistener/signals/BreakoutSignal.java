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
        boolean highWatch
) {
    public String displayCode() {
        return highWatch ? "HW_" + pattern.getCode() : pattern.getCode();
    }
}