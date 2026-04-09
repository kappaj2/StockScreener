package za.co.sfh.stocklistener.processor.ollama;

import tools.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import za.co.sfh.stocklistener.payloads.AggregateMinuteBar;
import za.co.sfh.stocklistener.payloads.BreakoutAnalysis;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Sends breakout candidate bars to a local Ollama model via Spring AI
 * and parses the structured JSON response back into a {@link BreakoutAnalysis}.
 *
 * The call is fire-and-forget async so it never blocks the Polygon message
 * processing queue.
 */
@Slf4j
@Service
public class OllamaBreakoutAnalyser {

    /** Extracts the first JSON object from the model response, ignoring surrounding prose. */
    private static final Pattern JSON_BLOCK = Pattern.compile("\\{[\\s\\S]+\\}");

    private final ChatClient chatClient;
    private final ObjectMapper objectMapper;

    public OllamaBreakoutAnalyser(ChatClient.Builder chatClientBuilder,
                                   ObjectMapper objectMapper) {
        this.chatClient = chatClientBuilder.build();
        this.objectMapper = objectMapper;
    }

    /**
     * Analyses a breakout candidate asynchronously.
     * Returns a {@link CompletableFuture} so the caller can attach a callback
     * without blocking the processing thread.
     *
     * @param symbol    ticker, e.g. "AAPL"
     * @param bars      rolling candle window (oldest → newest)
     * @param avgRange  rolling average candle range (high-low)
     * @param avgVolume rolling average volume
     */
    @Async
    public CompletableFuture<BreakoutAnalysis> analyseAsync(
            String symbol,
            List<AggregateMinuteBar> bars,
            double avgRange,
            double avgVolume) {

        try {
            String barsJson = objectMapper.writeValueAsString(
                    bars.stream().map(b -> Map.of(
                            "t",  b.startTimestampMs(),
                            "o",  b.open(),
                            "h",  b.high(),
                            "l",  b.low(),
                            "c",  b.close(),
                            "v",  b.volume(),
                            "vw", b.vwap()
                    )).toList()
            );

            String prompt = """
                    You are a breakout analyst. Analyse these 1-min bars for %s.
                    Avg candle range: %.4f | Avg volume: %.0f

                    Bars (oldest→newest, fields: t=timestamp o=open h=high l=low c=close v=volume vw=vwap):
                    %s

                    2-candle breakout criteria:
                    - Candle 1: green (c>o), body ≥50%% of range, move ≥5%%, volume ≥1.5x avg
                    - Candle 2: opens at/above C1 close, green, closes above C1 close
                    - Entry = C2 open | Stop = 8%% below entry | Target = 50%% above entry

                    Reply ONLY with a single JSON object — no markdown, no extra text:
                    {"confirmed":bool,"confidence":0-100,"entry":float,"stop":float,"target":float,"risk":"low|medium|high","notes":"brief reason"}
                    """.formatted(symbol, avgRange, avgVolume, barsJson);

            log.debug("[{}] Sending {} bars to Ollama for analysis", symbol, bars.size());

            String response = chatClient.prompt(prompt).call().content();
            log.debug("[{}] Ollama raw response: {}", symbol, response);

            BreakoutAnalysis analysis = parseResponse(symbol, response);
            log.info("[{}] Ollama analysis → confirmed={} confidence={} risk={} | {}",
                    symbol, analysis.confirmed(), analysis.confidence(),
                    analysis.risk(), analysis.notes());

            return CompletableFuture.completedFuture(analysis);

        } catch (Exception e) {
            log.error("[{}] Ollama analysis failed: {}", symbol, e.getMessage(), e);
            return CompletableFuture.completedFuture(BreakoutAnalysis.empty());
        }
    }

    private BreakoutAnalysis parseResponse(String symbol, String raw) {
        try {
            Matcher m = JSON_BLOCK.matcher(raw);
            String json = m.find() ? m.group() : raw;
            return objectMapper.readValue(json, BreakoutAnalysis.class);
        } catch (Exception e) {
            log.warn("[{}] Could not parse Ollama response as BreakoutAnalysis: {}", symbol, raw);
            return BreakoutAnalysis.empty();
        }
    }
}
