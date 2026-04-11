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
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Component
@RequiredArgsConstructor
public class MassiveWebSocketClient {

    private static final String WS_URI = "wss://delayed.massive.com/stocks";

    private final MessageProcessor messageProcessor;
    private final AtomicReference<WebSocket> activeWebSocket = new AtomicReference<>();

    @Value("${massive.api-key}")
    private String apiKey;

    @Value("${massive.symbols}")
    private String symbols;

    @Value("${massive.window.start}")
    private String windowStart;

    @Value("${massive.window.stop}")
    private String windowStop;

    @Value("${massive.cron.zone}")
    private String cronZone;

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        ZonedDateTime nowEt = ZonedDateTime.now(ZoneId.of(cronZone));
        LocalTime nowTime   = nowEt.toLocalTime();
        DayOfWeek day       = nowEt.getDayOfWeek();
        LocalTime start     = LocalTime.parse(windowStart);
        LocalTime stop      = LocalTime.parse(windowStop);

        if (day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY) {
            log.info("Today is {} (ET) — no trading session at weekends.", day);
            return;
        }

        if (nowTime.isAfter(start) && nowTime.isBefore(stop)) {
            log.info("ET time {} is within operating window ({} - {}), connecting...", nowTime, windowStart, windowStop);
            connect();
        } else {
            log.info("ET time {} is outside operating window ({} - {}), waiting for scheduled start.", nowTime, windowStart, windowStop);
        }
    }

    public void connect() {
        if (activeWebSocket.get() != null) {
            log.info("WebSocket is already connected.");
            return;
        }

        Thread.ofVirtual().name("massive-ws-client").start(() -> {
            try {
                HttpClient client = HttpClient.newHttpClient();
                WebSocket webSocket = client.newWebSocketBuilder()
                        .header("Authorization", "Bearer " + apiKey)
                        .buildAsync(URI.create(WS_URI), new StockMessageListener(apiKey, symbols, messageProcessor))
                        .join();
                activeWebSocket.set(webSocket);
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
            } finally {
                activeWebSocket.set(null);
            }
        });
    }

    public void disconnect() {
        WebSocket webSocket = activeWebSocket.getAndSet(null);
        if (webSocket != null) {
            log.info("Disconnecting WebSocket...");
            webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "Scheduled disconnect")
                    .thenRun(() -> log.info("WebSocket disconnect signal sent."));
            // The wait() in the virtual thread will be interrupted or return when the connection closes.
            synchronized (webSocket) {
                webSocket.notifyAll();
            }
        } else {
            log.info("WebSocket is not connected.");
        }
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
        public void onError(WebSocket webSocket, Throwable error) {
            log.error("WebSocket error", error);
            synchronized (webSocket) {
                webSocket.notifyAll();
            }
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            log.info("WebSocket closed — status: {}, reason: {}", statusCode, reason);
            synchronized (webSocket) {
                webSocket.notifyAll();
            }
            return null;
        }
    }
}
