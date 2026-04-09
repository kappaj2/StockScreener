package za.co.sfh.stocklistener.payloads;

public enum JohnWickType {
    NONE,
    BULLISH,   // long lower wick — rejection of lower prices, close near high
    BEARISH    // long upper wick — rejection of higher prices, close near low
}
