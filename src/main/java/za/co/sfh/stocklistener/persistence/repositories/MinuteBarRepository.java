package za.co.sfh.stocklistener.persistence.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import za.co.sfh.stocklistener.persistence.entities.MinuteBarEntity;

import java.time.Instant;
import java.util.List;

@Repository
public interface MinuteBarRepository extends JpaRepository<MinuteBarEntity, Long> {

    List<MinuteBarEntity> findBySymbolAndBarStartBetweenOrderByBarStartAsc(
            String symbol, Instant from, Instant to);

    List<MinuteBarEntity> findBySymbolOrderByBarStartDesc(String symbol);

    @Modifying
    @Query(value = "DELETE FROM minute_bar WHERE created_at < :cutoff LIMIT :batchSize", nativeQuery = true)
    int deleteBatchByCreatedAtBefore(Instant cutoff, int batchSize);
}
