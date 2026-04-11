package za.co.sfh.stocklistener.websocket;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import za.co.sfh.stocklistener.recorder.BarRecorder;
import za.co.sfh.stocklistener.signals.SignalStore;

import java.util.Optional;

/**
 * Orchestrates the daily trading session lifecycle.
 *
 * <ul>
 *   <li><b>04:00</b> — roll the bar recorder to a new date file, clear the signal store,
 *       then open the WebSocket connection.</li>
 *   <li><b>20:00</b> — close the WebSocket connection. Signal store is intentionally left
 *       intact for evening analysis.</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DailySessionScheduler {

    private final MassiveWebSocketClient webSocketClient;
    private final SignalStore signalStore;
    private final Optional<BarRecorder> barRecorder;

    /**
     * Session start: roll recorder file → clear signals → connect WebSocket.
     */
    @Scheduled(cron = "${massive.cron.start}", zone = "${massive.cron.zone}")
    public void sessionStart() {
        log.info("=== Daily session START ===");

        barRecorder.ifPresent(r -> {
            log.info("Rolling over bar recorder to new date file");
            r.rollover();
        });

        log.info("Clearing signal store for new trading day");
        signalStore.ackAll();

        webSocketClient.connect();
    }

    /**
     * Session stop: disconnect WebSocket. Signal store is preserved for review.
     */
    @Scheduled(cron = "${massive.cron.stop}", zone = "${massive.cron.zone}")
    public void sessionStop() {
        log.info("=== Daily session STOP ===");
        webSocketClient.disconnect();
    }
}
