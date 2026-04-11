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
import java.util.concurrent.locks.ReentrantLock;

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

    private final String outputDir;
    private final ReentrantLock lock = new ReentrantLock();
    private BufferedWriter writer;
    private final AtomicLong lineCount = new AtomicLong();

    public BarRecorder(@Value("${recorder.output-dir:src/test/resources/fixtures}") String outputDir)
            throws IOException {
        this.outputDir = outputDir;
        this.writer = openWriter();
    }

    /** Opens a fresh file for today's date and returns a writer to it. */
    private BufferedWriter openWriter() throws IOException {
        String date = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE);
        Path path = Path.of(outputDir, "bars-" + date + ".jsonl");
        Files.createDirectories(path.getParent());
        log.info("BarRecorder active — writing to {}", path.toAbsolutePath());
        return Files.newBufferedWriter(path, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    /**
     * Closes the current file and rolls over to a new bars-{today}.jsonl.
     * Called at session start (04:00) so each trading day gets its own file.
     */
    public void rollover() {
        lock.lock();
        try {
            try {
                writer.flush();
                writer.close();
                log.info("BarRecorder rolled over — {} messages in previous file", lineCount.get());
            } catch (IOException e) {
                log.warn("BarRecorder error closing previous file during rollover", e);
            }
            lineCount.set(0);
            writer = openWriter();
        } catch (IOException e) {
            log.error("BarRecorder failed to open new file during rollover", e);
        } finally {
            lock.unlock();
        }
    }

    public void record(String rawMessage) {
        lock.lock();
        try {
            writer.write(rawMessage);
            writer.newLine();
            long n = lineCount.incrementAndGet();
            if (n % 100 == 0) {
                log.info("BarRecorder: {} messages recorded", n);
            }
        } catch (IOException e) {
            log.error("BarRecorder failed to write message", e);
        } finally {
            lock.unlock();
        }
    }

    @PreDestroy
    public void close() {
        lock.lock();
        try {
            writer.flush();
            writer.close();
            log.info("BarRecorder closed — {} messages total", lineCount.get());
        } catch (IOException e) {
            log.error("BarRecorder failed to close writer", e);
        } finally {
            lock.unlock();
        }
    }
}
