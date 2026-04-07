package za.co.sfh.stocklistener.processor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import za.co.sfh.stocklistener.payloads.BreakoutAnalysis;
import za.co.sfh.stocklistener.processor.states.SymbolState;

import java.lang.reflect.Field;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Replays a recorded fixture file through the real handler stack.
 *
 * The fixture file is produced by running the app with --spring.profiles.active=record.
 * It is committed to src/test/resources/fixtures/bars.jsonl once you have captured
 * enough data.
 *
 * The test is skipped automatically when no fixture file is present so that CI
 * never fails on a fresh checkout.
 */
class BarReplayTest {

    private static final String FIXTURE = "fixtures/bars.jsonl";

    @Test
    void replayFixture() throws Exception {
        URL resource = getClass().getClassLoader().getResource(FIXTURE);
        if (resource == null) {
            System.out.println("Skipping BarReplayTest — fixture not yet recorded (" + FIXTURE + ")");
            return;
        }

        List<String> lines = Files.readAllLines(Path.of(resource.toURI()));
        assertThat(lines).as("fixture must not be empty").isNotEmpty();

        // Wire up the handler stack without Spring context
        ObjectMapper objectMapper = JsonMapper.builder()
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .disable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                .build();

        // Stub Ollama — returns empty analysis so breakout candidates are logged but not emitted
        OllamaBreakoutAnalyser ollamaAnalyser = mock(OllamaBreakoutAnalyser.class);
        when(ollamaAnalyser.analyseAsync(anyString(), any(), anyDouble(), anyDouble(), anyDouble()))
                .thenReturn(CompletableFuture.completedFuture(BreakoutAnalysis.empty()));

        // Stub SignalStore — captures any confirmed signals without needing Spring context
        za.co.sfh.stocklistener.signals.SignalStore signalStore =
                mock(za.co.sfh.stocklistener.signals.SignalStore.class);

        AggregateMinuteBarHandler handler = new AggregateMinuteBarHandler(objectMapper, ollamaAnalyser, signalStore);
        injectFilterDefaults(handler);

        MessageProcessor processor = new MessageProcessor(objectMapper, List.of(handler), Optional.empty());
        processor.startProcessing();

        for (String line : lines) {
            processor.offer(line);
        }

        // Let the virtual-thread processor drain the queue
        Thread.sleep(200);

        Map<String, SymbolState> stateMap = extractStateMap(handler);
        System.out.printf("Replay complete: %d symbols in stateMap%n", stateMap.size());
        assertThat(stateMap).isNotEmpty();
    }

    /** Inject the @Value filter defaults so the handler works without a Spring context. */
    private void injectFilterDefaults(AggregateMinuteBarHandler handler) throws Exception {
        setField(handler, "minClose", 2.0);
        setField(handler, "minVolume", 50_000L);
        setField(handler, "minConfidence", 70);
    }

    @SuppressWarnings("unchecked")
    private Map<String, SymbolState> extractStateMap(AggregateMinuteBarHandler handler) throws Exception {
        Field f = AggregateMinuteBarHandler.class.getDeclaredField("stateMap");
        f.setAccessible(true);
        return (ConcurrentHashMap<String, SymbolState>) f.get(handler);
    }

    private void setField(Object target, String name, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }
}
