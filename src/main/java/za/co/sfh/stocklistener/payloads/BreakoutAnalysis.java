package za.co.sfh.stocklistener.payloads;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Structured response from the Ollama breakout analyser.
 * Maps directly from the JSON the LLM returns.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record BreakoutAnalysis(
        boolean confirmed,
        int confidence,
        double entry,
        double stop,
        double target,
        String risk,
        String notes
) {
    /** Returned when the LLM call fails or returns unparseable output. */
    public static BreakoutAnalysis empty() {
        return new BreakoutAnalysis(false, 0, 0.0, 0.0, 0.0, "unknown", "Analysis unavailable");
    }
}
