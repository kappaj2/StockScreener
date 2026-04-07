package za.co.sfh.stocklistener.processor;

import tools.jackson.databind.JsonNode;

public interface MessageHandler {

    boolean supports(String eventType);

    void handle(JsonNode message);
}
