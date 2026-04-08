package za.co.sfh.stocklistener.recorder;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Activated only under the "record" Spring profile.
 *
 * Appends every raw WebSocket message (one JSON array per line) to a .jsonl
 * fixture file so it can be replayed in unit tests.
 *
 * Usage:  java -jar app.jar --spring.profiles.active=record
 *         or set SPRING_PROFILES_ACTIVE=record in the environment.
 */
@Slf4j
@Component
@Profile("record")
public class BarRecorder {

    private final BufferedWriter writer;
    private final AtomicLong lineCount = new AtomicLong();

    public BarRecorder(@Value("${recorder.output-dir:src/test/resources/fixtures}") String outputDir)
            throws IOException {
        String date = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE);
        Path path = Path.of(outputDir, "bars-" + date + ".jsonl");
        Files.createDirectories(path.getParent());
        this.writer = Files.newBufferedWriter(path,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND);
        log.info("BarRecorder active — writing to {}", path.toAbsolutePath());
    }

    public void record(String rawMessage) {
        try {
            synchronized (writer) {
                writer.write(rawMessage);
                writer.newLine();
            }
            long n = lineCount.incrementAndGet();
            if (n % 100 == 0) {
                log.info("BarRecorder: {} messages recorded", n);
            }
        } catch (IOException e) {
            log.error("BarRecorder failed to write message", e);
        }
    }

    @PreDestroy
    public void close() {
        try {
            synchronized (writer) {
                writer.flush();
                writer.close();
            }
            log.info("BarRecorder closed — {} messages total", lineCount.get());
        } catch (IOException e) {
            log.error("BarRecorder failed to close writer", e);
        }
    }
}
