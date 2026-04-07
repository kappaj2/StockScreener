package za.co.sfh.stocklistener.websocket;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import za.co.sfh.stocklistener.processor.MessageProcessor;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.concurrent.CompletionStage;

@Slf4j
@Component
@RequiredArgsConstructor
public class MassiveWebSocketClient {

    private static final String WS_URI = "wss://delayed.massive.com/stocks";

    private final MessageProcessor messageProcessor;

    @Value("${massive.api-key}")
    private String apiKey;

    @Value("${massive.symbols}")
    private String symbols;

    @EventListener(ApplicationReadyEvent.class)
    public void connect() {
        Thread.ofVirtual().name("massive-ws-client").start(() -> {
            try {
                HttpClient client = HttpClient.newHttpClient();
                WebSocket webSocket = client.newWebSocketBuilder()
                        .header("Authorization", "Bearer " + apiKey)
                        .buildAsync(URI.create(WS_URI), new StockMessageListener(apiKey, symbols, messageProcessor))
                        .join();
                log.info("WebSocket connection initiated to {}", WS_URI);
                // Keep the virtual thread alive while the connection is open
                synchronized (webSocket) {
                    webSocket.wait();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("WebSocket client thread interrupted");
            } catch (Exception e) {
                log.error("Failed to connect to Massive WebSocket", e);
            }
        });
    }

    private static class StockMessageListener implements WebSocket.Listener {

        private final StringBuilder buffer = new StringBuilder();
        private final String apiKey;
        private final String symbols;
        private final MessageProcessor messageProcessor;

        StockMessageListener(String apiKey, String symbols, MessageProcessor messageProcessor) {
            this.apiKey = apiKey;
            this.symbols = symbols;
            this.messageProcessor = messageProcessor;
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            log.info("Connected to {}", WS_URI);
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            buffer.append(data);
            if (last) {
                String message = buffer.toString();
                buffer.setLength(0);
                if (message.contains("\"connected\"")) {
                    String authMsg = "{\"action\":\"auth\",\"params\":\"" + apiKey + "\"}";
                    webSocket.sendText(authMsg, true);
                    log.info("Sent auth");
                } else if (message.contains("\"auth_success\"")) {
                    String subscribeMsg = "{\"action\":\"subscribe\",\"params\":\"" + symbols + "\"}";
                    webSocket.sendText(subscribeMsg, true);
                    log.info("Subscribed to {}", symbols);
                } else {
                    messageProcessor.offer(message);
                }
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            log.info("WebSocket closed — status: {}, reason: {}", statusCode, reason);
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            log.error("WebSocket error", error);
        }
    }
}
