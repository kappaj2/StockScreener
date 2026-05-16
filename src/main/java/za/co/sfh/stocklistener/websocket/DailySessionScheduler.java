package za.co.sfh.stocklistener.websocket;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import za.co.sfh.stocklistener.recorder.BarRecorder;
import za.co.sfh.stocklistener.signals.SignalStore;

import java.util.Optional;

/**
 * Daily housekeeping at session start (04:00 ET weekdays):
 * rolls the bar recorder to a new date file, clears the signal store,
 * and ensures the WebSocket is connected (no-op if already up).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DailySessionScheduler {

    private final MassiveWebSocketClient webSocketClient;
    private final SignalStore signalStore;
    private final Optional<BarRecorder> barRecorder;

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
}
