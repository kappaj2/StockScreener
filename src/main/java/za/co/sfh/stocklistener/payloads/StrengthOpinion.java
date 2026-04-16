package za.co.sfh.stocklistener.payloads;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Structured response from the Ollama strength analyser.
 * The LLM is asked whether it agrees with the momentum tier computed by the rule-based scanner.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record StrengthOpinion(
        String assessment,  // "AGREES" | "DISAGREES" | "UNCERTAIN"
        int    confidence,  // 0–100: how confident Ollama is in its assessment
        String reasoning    // brief explanation
) {
    /** Returned when the LLM call fails or the response cannot be parsed. */
    public static StrengthOpinion empty() {
        return new StrengthOpinion("UNCERTAIN", 0, "Analysis unavailable");
    }
}
