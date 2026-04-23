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
import java.time.Duration;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Component
@RequiredArgsConstructor
public class MassiveWebSocketClient {

    private static final String WS_URI = "wss://delayed.massive.com/stocks";

    private final MessageProcessor messageProcessor;
    private final AtomicReference<WebSocket> activeWebSocket = new AtomicReference<>();
    private final AtomicBoolean intentionalDisconnect = new AtomicBoolean(false);
    private final AtomicInteger retryCount = new AtomicInteger(0);

    @Value("${massive.enabled:true}")
    private boolean enabled;

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

    @Value("${massive.retry.initial-delay-seconds:5}")
    private long retryInitialDelaySeconds;

    @Value("${massive.retry.max-delay-seconds:60}")
    private long retryMaxDelaySeconds;

    /** 0 = unlimited retries within the trading window */
    @Value("${massive.retry.max-attempts:0}")
    private int retryMaxAttempts;

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        if (!enabled) {
            log.info("Massive WebSocket disabled via massive.enabled=false — skipping startup.");
            return;
        }
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
        if (!enabled) {
            log.info("Massive WebSocket disabled — connect() is a no-op.");
            return;
        }
        if (activeWebSocket.get() != null) {
            log.info("WebSocket is already connected.");
            return;
        }

        intentionalDisconnect.set(false);

        Thread.ofVirtual().name("massive-ws-client").start(() -> {
            try {
                HttpClient client = HttpClient.newHttpClient();
                WebSocket webSocket = client.newWebSocketBuilder()
                        .header("Authorization", "Bearer " + apiKey)
                        .buildAsync(URI.create(WS_URI), new StockMessageListener(
                                apiKey, symbols, messageProcessor,
                                this::scheduleReconnect,
                                () -> retryCount.set(0)))
                        .join();
                activeWebSocket.set(webSocket);
                log.info("WebSocket connection initiated to {}", WS_URI);
                synchronized (webSocket) {
                    webSocket.wait();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("WebSocket client thread interrupted");
            } catch (Exception e) {
                log.error("Failed to connect to Massive WebSocket", e);
                scheduleReconnect();
            } finally {
                activeWebSocket.set(null);
            }
        });
    }

    public void disconnect() {
        intentionalDisconnect.set(true);
        retryCount.set(0);
        WebSocket webSocket = activeWebSocket.getAndSet(null);
        if (webSocket != null) {
            log.info("Disconnecting WebSocket...");
            webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "Scheduled disconnect")
                    .thenRun(() -> log.info("WebSocket disconnect signal sent."));
            synchronized (webSocket) {
                webSocket.notifyAll();
            }
        } else {
            log.info("WebSocket is not connected.");
        }
    }

    private void scheduleReconnect() {
        if (intentionalDisconnect.get()) {
            log.info("Intentional disconnect — skipping reconnect.");
            return;
        }

        ZonedDateTime nowEt = ZonedDateTime.now(ZoneId.of(cronZone));
        LocalTime nowTime   = nowEt.toLocalTime();
        DayOfWeek day       = nowEt.getDayOfWeek();
        LocalTime start     = LocalTime.parse(windowStart);
        LocalTime stop      = LocalTime.parse(windowStop);

        if (day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY) {
            log.info("Weekend — not reconnecting.");
            return;
        }

        if (!nowTime.isAfter(start) || !nowTime.isBefore(stop)) {
            log.info("Outside trading window ({} - {}) — not reconnecting.", windowStart, windowStop);
            retryCount.set(0);
            return;
        }

        int attempt = retryCount.incrementAndGet();
        if (retryMaxAttempts > 0 && attempt > retryMaxAttempts) {
            log.warn("Max reconnect attempts ({}) reached — giving up.", retryMaxAttempts);
            retryCount.set(0);
            return;
        }

        // Exponential backoff: initialDelay * 2^(attempt-1), capped at maxDelay
        long delaySeconds = Math.min(retryInitialDelaySeconds * (1L << (attempt - 1)), retryMaxDelaySeconds);
        log.info("Scheduling reconnect attempt #{} in {}s...", attempt, delaySeconds);

        Thread.ofVirtual().name("massive-ws-reconnect-" + attempt).start(() -> {
            try {
                Thread.sleep(Duration.ofSeconds(delaySeconds));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            // Re-check trading window after the sleep
            ZonedDateTime nowEt2  = ZonedDateTime.now(ZoneId.of(cronZone));
            LocalTime nowTime2    = nowEt2.toLocalTime();
            DayOfWeek day2        = nowEt2.getDayOfWeek();
            LocalTime start2      = LocalTime.parse(windowStart);
            LocalTime stop2       = LocalTime.parse(windowStop);
            if (day2 == DayOfWeek.SATURDAY || day2 == DayOfWeek.SUNDAY
                    || !nowTime2.isAfter(start2) || !nowTime2.isBefore(stop2)) {
                log.info("Trading window closed before reconnect attempt #{} — aborting.", attempt);
                retryCount.set(0);
                return;
            }
            activeWebSocket.set(null);
            connect();
        });
    }

    private static class StockMessageListener implements WebSocket.Listener {

        private final StringBuilder buffer = new StringBuilder();
        private final String apiKey;
        private final String symbols;
        private final MessageProcessor messageProcessor;
        private final Runnable onDisconnect;
        private final Runnable onConnected;

        StockMessageListener(String apiKey, String symbols, MessageProcessor messageProcessor,
                             Runnable onDisconnect, Runnable onConnected) {
            this.apiKey = apiKey;
            this.symbols = symbols;
            this.messageProcessor = messageProcessor;
            this.onDisconnect = onDisconnect;
            this.onConnected = onConnected;
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            log.info("Connected to {}", WS_URI);
            onConnected.run();
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
            log.error("WebSocket error — will attempt reconnect", error);
            synchronized (webSocket) {
                webSocket.notifyAll();
            }
            onDisconnect.run();
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            log.info("WebSocket closed — status: {}, reason: {} — will attempt reconnect", statusCode, reason);
            synchronized (webSocket) {
                webSocket.notifyAll();
            }
            onDisconnect.run();
            return null;
        }
    }
}
