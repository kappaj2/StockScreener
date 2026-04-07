package za.co.sfh.stocklistener.processor;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

@Slf4j
@Component
public class ProcessStatusMessage implements MessageHandler {

    @Override
    public boolean supports(String eventType) {
        return "status".equals(eventType);
    }

    @Override
    public void handle(JsonNode message) {
        log.info("Status — {}: {}", message.path("status").asText(), message.path("message").asText());
    }
}
