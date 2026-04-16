package za.co.sfh.stocklistener.processor.ollama;

import tools.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import za.co.sfh.stocklistener.payloads.StrengthOpinion;
import za.co.sfh.stocklistener.processor.indicators.LinearRegressionIndicator;
import za.co.sfh.stocklistener.processor.indicators.MacdIndicator;
import za.co.sfh.stocklistener.processor.indicators.RocIndicator;
import za.co.sfh.stocklistener.processor.indicators.RsiIndicator;
import za.co.sfh.stocklistener.processor.indicators.VolumeProfileIndicator;

import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Asks a local Ollama model to validate the momentum tier computed by the rule-based scanner.
 *
 * <p>The call is always {@code @Async} — it never blocks the message processing queue.
 * The result is returned as a {@link CompletableFuture} so the caller can attach a log
 * callback without waiting.
 *
 * <p>The Ollama opinion is <em>observational only</em> at this stage: it does not gate or
 * modify signal emission. It exists to gather a second opinion so the thresholds in
 * {@code MomentumStrengthScanner} can be tuned against Ollama's feedback over time.
 */
@Slf4j
@Service
public class OllamaStrengthScanner {

    private static final Pattern JSON_BLOCK = Pattern.compile("\\{[\\s\\S]+\\}");

    private final ChatClient  chatClient;
    private final ObjectMapper objectMapper;

    public OllamaStrengthScanner(ChatClient.Builder chatClientBuilder,
                                  ObjectMapper objectMapper) {
        this.chatClient   = chatClientBuilder.build();
        this.objectMapper = objectMapper;
    }

    /**
     * Sends the indicator readings and rule-based tier to Ollama for a second-opinion assessment.
     *
     * @param symbol    ticker symbol, e.g. "AAPL"
     * @param tierLabel the tier string produced by {@code MomentumStrengthScanner},
     *                  e.g. "HIGH_MOMENTUM_ALERT"
     * @param score     numeric score that triggered the tier
     * @param rsi       RSI indicator result
     * @param roc       ROC indicator result
     * @param macd      MACD indicator result
     * @param linReg    linear regression result
     * @param vol       volume profile result
     * @return a {@link CompletableFuture} resolving to a {@link StrengthOpinion}; never null
     */
    @Async
    public CompletableFuture<StrengthOpinion> analyseAsync(
            String symbol,
            String tierLabel,
            int    score,
            RsiIndicator.RsiResult                  rsi,
            RocIndicator.RocResult                  roc,
            MacdIndicator.MacdResult                macd,
            LinearRegressionIndicator.LinRegResult  linReg,
            VolumeProfileIndicator.VolumeProfileResult vol) {

        try {
            String prompt = buildPrompt(symbol, tierLabel, score, rsi, roc, macd, linReg, vol);

            log.debug("[{}] Sending momentum context to Ollama (tier={})", symbol, tierLabel);

            String response = chatClient.prompt(prompt).call().content();
            log.debug("[{}] Ollama strength raw response: {}", symbol, response);

            StrengthOpinion opinion = parseResponse(symbol, response);
            return CompletableFuture.completedFuture(opinion);

        } catch (Exception e) {
            log.warn("[{}] Ollama strength analysis failed: {}", symbol, e.getMessage());
            return CompletableFuture.completedFuture(StrengthOpinion.empty());
        }
    }

    // ── Internal helpers ─────────────────────────────────────────────────────

    private String buildPrompt(String symbol, String tierLabel, int score,
                                RsiIndicator.RsiResult rsi,
                                RocIndicator.RocResult roc,
                                MacdIndicator.MacdResult macd,
                                LinearRegressionIndicator.LinRegResult linReg,
                                VolumeProfileIndicator.VolumeProfileResult vol) {

        String rsiStr   = rsi.isValid()    ? "%.1f (overbought=%b, bullish=%b)".formatted(rsi.rsi(), rsi.overbought(), rsi.bullishMomentum()) : "n/a";
        String rocStr   = roc.isValid()    ? "%.2f%% (accelerating=%b, surge=%b)".formatted(roc.roc(), roc.accelerating(), roc.surge()) : "n/a";
        String macdStr  = macd.isValid()   ? "macd=%.4f signal=%.4f hist=%.4f (crossover=%b, aboveZero=%b, expanding=%b)"
                .formatted(macd.macdLine(), macd.signalLine(), macd.histogram(),
                           macd.bullishCrossover(), macd.aboveZero(), macd.expandingHistogram()) : "n/a";
        String linStr   = linReg.isValid() ? "slope=%.5f r2=%.3f (steep=%b, strong=%b)"
                .formatted(linReg.slope(), linReg.r2(), linReg.steeplyPositive(), linReg.strongTrend()) : "n/a";
        String volStr   = vol.isValid()    ? "ratio=%.2fx (surge=%b, conviction=%b, declining=%b)"
                .formatted(vol.ratio(), vol.volumeSurge(), vol.strongConviction(), vol.decliningVolume()) : "n/a";

        return """
                You are a quantitative momentum analyst reviewing a pre-market breakout scanner result for %s.

                The rule-based scanner assigned tier: %s (score: %d/14)

                Indicator readings:
                  RSI (14):        %s
                  ROC (10-bar):    %s
                  MACD (12/26/9):  %s
                  Linear Reg:      %s
                  Volume profile:  %s

                Based solely on these indicator readings, do you agree with the tier assessment?
                Consider:
                - Is RSI confirming bullish momentum (above 50, ideally above 60)?
                - Is the rate of change positive and accelerating?
                - Is MACD trending upward and above zero?
                - Is the linear regression slope clearly positive and the R² high?
                - Is volume above the baseline (ratio > 1.0)?

                Reply ONLY with a single JSON object — no markdown, no extra text:
                {"assessment":"AGREES|DISAGREES|UNCERTAIN","confidence":0-100,"reasoning":"one concise sentence"}
                """.formatted(symbol, tierLabel, score, rsiStr, rocStr, macdStr, linStr, volStr);
    }

    private StrengthOpinion parseResponse(String symbol, String raw) {
        try {
            Matcher m    = JSON_BLOCK.matcher(raw);
            String  json = m.find() ? m.group() : raw;
            return objectMapper.readValue(json, StrengthOpinion.class);
        } catch (Exception e) {
            log.warn("[{}] Could not parse Ollama strength response: {}", symbol, raw);
            return StrengthOpinion.empty();
        }
    }
}
