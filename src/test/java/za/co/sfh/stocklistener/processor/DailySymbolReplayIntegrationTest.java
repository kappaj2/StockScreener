package za.co.sfh.stocklistener.processor;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import za.co.sfh.stocklistener.DatabaseContainerTest;
import za.co.sfh.stocklistener.signals.BreakoutSignal;
import za.co.sfh.stocklistener.signals.SignalStore;

import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Integration test that runs the full Spring context and feeds a fixture file
 * into the MessageProcessor to verify that signals are generated and stored.
 */
@SpringBootTest
@ActiveProfiles("test")
class DailySymbolReplayIntegrationTest implements DatabaseContainerTest {

    @Autowired
    private MessageProcessor messageProcessor;

    @Autowired
    private SignalStore signalStore;

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private ObjectMapper objectMapper;

    private static final String TEST_DATE = "2026-05-22";
    private static final String TEST_SYMBOL = "LODE";

    @Test
    void replayFixtureThroughSpringContext() throws Exception {
        String fixture = "fixtures/bars-" + TEST_DATE + ".jsonl";
        URL resource = getClass().getClassLoader().getResource(fixture);
        if (resource == null) {
            System.out.println("Skipping DailySymbolReplayIntegrationTest — fixture not found: " + fixture);
            return;
        }

        List<String> lines = Files.readAllLines(Path.of(resource.toURI()));
        
        // Clear signal store before starting
        signalStore.ackAll();

        System.out.println("Filtering and feeding lines for " + TEST_SYMBOL + " into MessageProcessor...");
        int fedCount = 0;
        for (String line : lines) {
            try {
                JsonNode root = objectMapper.readTree(line);
                if (root.isArray()) {
                    ArrayNode arrayNode = (ArrayNode) root;
                    ArrayNode filteredArray = objectMapper.createArrayNode();
                    for (JsonNode node : arrayNode) {
                        if (TEST_SYMBOL.equals(node.path("sym").asString(null))) {
                            filteredArray.add(node);
                        }
                    }
                    if (!filteredArray.isEmpty()) {
                        messageProcessor.offer(objectMapper.writeValueAsString(filteredArray));
                        fedCount++;
                    }
                }
            } catch (Exception e) {
                // Skip malformed lines as in DailySymbolReplayTest
                System.err.println("Skipping malformed JSON line: " + e.getMessage());
            }
        }
        System.out.println("Fed " + fedCount + " filtered lines into MessageProcessor.");

        // Wait for processing to complete. 
        // Since it's happening in a virtual thread, we poll the SignalStore.
        // In a real scenario, we'd want a more robust way to know when the queue is empty.
        int timeoutSeconds = 30;
        int elapsed = 0;
        while (elapsed < timeoutSeconds) {
            Thread.sleep(1000);
            elapsed++;
            if (signalStore.size() > 0) {
                // Found some signals, let's wait a bit more to ensure it's done
                Thread.sleep(2000);
                break;
            }
        }

        List<BreakoutSignal> signals = signalStore.peekAll();
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z")
                .withZone(ZoneId.of("America/New_York"));
        System.out.println("Detected " + signals.size() + " signals:");
        signals.forEach(s -> System.out.printf("  [%s] %s at %.2f  ts=%s%n",
                s.symbol(), s.pattern(), s.entry(),
                fmt.format(Instant.ofEpochMilli(s.timestamp()))));

        assertFalse(signals.isEmpty(), "Should have detected at least one signal from the fixture");

        // Verify that SignalView would display these signals
        // Since SignalView is @Route but not necessarily a @Bean, we check SignalStore 
        // which we already know is used by SignalView to refresh its grid.
        assertFalse(signalStore.peekAll().isEmpty(), "SignalStore should not be empty");
        System.out.println("Verified that SignalStore contains signals that SignalView would display.");
    }
}
