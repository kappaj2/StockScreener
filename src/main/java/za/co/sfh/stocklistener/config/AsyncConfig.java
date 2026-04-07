package za.co.sfh.stocklistener.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * Enables Spring's @Async support.
 *
 * With spring.threads.virtual.enabled=true (Spring Boot 4), @Async methods
 * automatically run on virtual threads — no explicit executor configuration needed.
 */
@Configuration
@EnableAsync
public class AsyncConfig {
}
