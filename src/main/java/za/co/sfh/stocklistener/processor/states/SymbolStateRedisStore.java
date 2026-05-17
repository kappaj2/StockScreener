package za.co.sfh.stocklistener.processor.states;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.Optional;

/**
 * Persists and rehydrates {@link SymbolState} scalars + recent candles in Redis.
 * Keys are stored as {@code sym:{symbol}} — plain JSON strings.
 *
 * Virtual threads handle blocking Redis I/O without holding OS threads.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SymbolStateRedisStore {

    private static final String KEY_PREFIX = "sym:";

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public void save(SymbolState state) {
        try {
            String json = objectMapper.writeValueAsString(state.toSnapshot());
            redis.opsForValue().set(KEY_PREFIX + state.getSymbol(), json);
            log.debug("Pushing to redis [symbol: {}]",state.getSymbol());
        } catch (Exception e) {
            log.warn("[{}] Failed to persist state to Redis", state.getSymbol(), e);
        }
    }

    public Optional<SymbolStateSnapshot> loadSnapshot(String symbol) {
        try {
            String json = redis.opsForValue().get(KEY_PREFIX + symbol);
            if (json == null) return Optional.empty();
            return Optional.of(objectMapper.readValue(json, SymbolStateSnapshot.class));
        } catch (Exception e) {
            log.warn("[{}] Failed to load snapshot from Redis", symbol, e);
            return Optional.empty();
        }
    }

    public Optional<SymbolState> load(String symbol) {
        try {
            String json = redis.opsForValue().get(KEY_PREFIX + symbol);
            if (json == null) return Optional.empty();
            SymbolStateSnapshot snapshot = objectMapper.readValue(json, SymbolStateSnapshot.class);
            SymbolState state = new SymbolState();
            state.restoreFrom(snapshot);
            log.debug("[{}] State rehydrated from Redis (bars={}, ema9={})",
                    symbol, snapshot.totalBars(), snapshot.ema9());
            return Optional.of(state);
        } catch (Exception e) {
            log.warn("[{}] Failed to load state from Redis — starting fresh", symbol, e);
            return Optional.empty();
        }
    }
}
