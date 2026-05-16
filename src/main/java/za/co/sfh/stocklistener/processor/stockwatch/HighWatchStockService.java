package za.co.sfh.stocklistener.processor.stockwatch;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.Caching;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import za.co.sfh.stocklistener.config.CacheConfig;
import za.co.sfh.stocklistener.persistence.entities.HighWatchStockEntity;
import za.co.sfh.stocklistener.persistence.repositories.HighWatchStockRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class HighWatchStockService {

    private final HighWatchStockRepository repository;

    @Value("${stockwatch.valid-days:60}")
    private int validDays;

    @Cacheable(value = CacheConfig.HIGH_WATCH_STOCKS, key = "'active'")
    public List<HighWatchStockEntity> findActive() {
        return repository.findActive(LocalDateTime.now());
    }

    // Key is normalised to uppercase so cache hits regardless of caller casing.
    @Cacheable(value = CacheConfig.HIGH_WATCH_STOCKS, key = "#symbol.toUpperCase()")
    public Optional<HighWatchStockEntity> findBySymbol(String symbol) {
        return repository.findById(symbol.toUpperCase());
    }

    @Transactional
    @CacheEvict(value = CacheConfig.HIGH_WATCH_STOCKS, allEntries = true)
    public HighWatchStockEntity addStock(String symbol, String sourceList) {
        String upper = symbol.toUpperCase();
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime validTo = now.plusDays(validDays);
        if (repository.existsById(upper)) {
            repository.updateValidDates(upper, now, validTo);
            log.info("Updated {} in high-watch-stocks (valid until {})", upper, validTo);
            return repository.findById(upper).orElseThrow();
        }
        HighWatchStockEntity entity = HighWatchStockEntity.builder()
                .symbol(upper)
                .validFrom(now)
                .validTo(validTo)
                .sourceList(sourceList)
                .build();
        HighWatchStockEntity saved = repository.save(entity);
        log.info("Added {} to high-watch-stocks (valid until {})", saved.getSymbol(), saved.getValidTo());
        return saved;
    }

    @Transactional
    @Caching(evict = {
            @CacheEvict(value = CacheConfig.HIGH_WATCH_STOCKS, key = "#symbol.toUpperCase()"),
            @CacheEvict(value = CacheConfig.HIGH_WATCH_STOCKS, key = "'active'")
    })
    public boolean deleteStock(String symbol) {
        String upper = symbol.toUpperCase();
        if (!repository.existsById(upper)) {
            return false;
        }
        repository.deleteById(upper);
        log.info("Removed {} from high-watch-stocks", upper);
        return true;
    }
}