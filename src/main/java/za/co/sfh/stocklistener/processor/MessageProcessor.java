package za.co.sfh.stocklistener.processor;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import za.co.sfh.stocklistener.recorder.BarRecorder;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedQueue;

@Slf4j
@Component
public class MessageProcessor implements MessageHandler {

    private final ConcurrentLinkedQueue<String> queue = new ConcurrentLinkedQueue<>();
    private final ObjectMapper objectMapper;
    private final List<MessageHandler> handlers;
    private final Optional<BarRecorder> recorder;

    public MessageProcessor(ObjectMapper objectMapper, List<MessageHandler> allHandlers, Optional<BarRecorder> recorder) {
        this.objectMapper = objectMapper;
        this.handlers = allHandlers.stream()
                .filter(h -> !(h instanceof MessageProcessor))
                .toList();
        this.recorder = recorder;
    }

    public void offer(String message) {
        queue.offer(message);
    }

    @Override
    public boolean supports(String eventType) {
        return handlers.stream().anyMatch(h -> h.supports(eventType));
    }

    @Override
    public void handle(JsonNode node) {
        var ev = node.path("ev").asString(null);
        if (ev == null) {
            log.warn("Message has no 'ev' field: {}", node);
            return;
        }
        handlers.stream()
                .filter(h -> h.supports(ev))
                .findFirst()
                .ifPresentOrElse(
                        h -> h.handle(node),
                        () -> log.debug("No handler for ev='{}': {}", ev, node)
                );
    }

    @EventListener(ApplicationReadyEvent.class)
    public void startProcessing() {
        Thread.ofVirtual().name("message-processor").start(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                var message = queue.poll();
                if (message != null) {
                    process(message);
                } else {
                    try {
                        Thread.sleep(1);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        });
    }

    private void process(String raw) {
        recorder.ifPresent(r -> r.record(raw));
        try {
            var array = objectMapper.readTree(raw);
            for (JsonNode node : array) {
                handle(node);
            }
        } catch (Exception e) {
            log.error("Failed to process message: {}", raw, e);
        }
    }
}
