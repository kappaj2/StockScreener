package za.co.sfh.stocklistener.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import za.co.sfh.stocklistener.processor.states.SymbolStateRedisStore;
import za.co.sfh.stocklistener.processor.states.SymbolStateSnapshot;

import java.util.Map;
import java.util.Set;

@Slf4j
@RestController
@RequestMapping("/api/redis")
@RequiredArgsConstructor
@Tag(name = "Redis Store", description = "Interrogate and manage the Redis symbol-state store. "
        + "All operations are scoped to keys matching the 'sym:*' pattern — other Redis keys are never touched.")
public class RedisStoreController {

    private static final String KEY_PATTERN = "sym:*";

    private final StringRedisTemplate redis;
    private final SymbolStateRedisStore redisStore;

    @Operation(
            summary = "Count Redis entries",
            description = "Returns the total number of `sym:*` keys currently held in Redis. "
                    + "No filtering is applied — every persisted symbol counts.")
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Key count returned successfully",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(example = "{\"count\": 42}"),
                            examples = @ExampleObject(name = "example", value = "{\"count\": 42}")
                    )
            )
    })
    @GetMapping("/count")
    public ResponseEntity<Map<String, Long>> count() {
        Set<String> keys = redis.keys(KEY_PATTERN);
        long count = keys == null ? 0L : keys.size();
        log.debug("GET /api/redis/count → {}", count);
        return ResponseEntity.ok(Map.of("count", count));
    }

    @Operation(
            summary = "Lookup symbol state in Redis",
            description = "Returns the persisted `SymbolStateSnapshot` for the given ticker. "
                    + "The snapshot contains rolling bar history, indicator accumulators (EMA, VWAP, RSI inputs), "
                    + "and session-level high/low values as last written by the message processing pipeline.")
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Snapshot found and returned",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = SymbolStateSnapshot.class)
                    )
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "No Redis entry exists for the given symbol",
                    content = @Content
            )
    })
    @GetMapping("/symbol/{symbol}")
    public ResponseEntity<SymbolStateSnapshot> symbol(
            @Parameter(description = "Ticker symbol, e.g. AAPL", example = "AAPL")
            @PathVariable String symbol) {
        log.debug("GET /api/redis/symbol/{}", symbol);
        return redisStore.loadSnapshot(symbol)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @Operation(
            summary = "Flush the Redis store",
            description = "Deletes **all** `sym:*` keys from Redis. "
                    + "In-memory `SymbolState` objects held by the processing pipeline are not affected, "
                    + "so live scanning continues uninterrupted. "
                    + "Useful for forcing a clean slate without restarting the application.")
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Flush completed — returns the number of keys deleted",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(example = "{\"deleted\": 17}"),
                            examples = @ExampleObject(name = "example", value = "{\"deleted\": 17}")
                    )
            )
    })
    @PutMapping("/flush")
    public ResponseEntity<Map<String, Long>> flush() {
        Set<String> keys = redis.keys(KEY_PATTERN);
        long deleted = 0L;
        if (keys != null && !keys.isEmpty()) {
            deleted = redis.delete(keys);
        }
        log.info("PUT /api/redis/flush — deleted {} key(s)", deleted);
        return ResponseEntity.ok(Map.of("deleted", deleted));
    }
}