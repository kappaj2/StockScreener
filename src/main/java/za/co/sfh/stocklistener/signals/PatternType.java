package za.co.sfh.stocklistener.signals;

/**
 * Canonical names for all pattern scanner types.
 * Used in {@link BreakoutSignal#pattern()} so callers compare enum constants
 * rather than magic strings.
 */
public enum PatternType {
    BREAKOUT,
    UNSHARPEN_MASK,
    STRONG_CLIMB,
    INVERTED_VEE
}
