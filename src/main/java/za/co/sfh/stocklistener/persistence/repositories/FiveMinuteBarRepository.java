package za.co.sfh.stocklistener.persistence.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import za.co.sfh.stocklistener.persistence.entities.FiveMinuteBarEntity;
import za.co.sfh.stocklistener.persistence.entities.FiveMinuteBarId;

import java.time.Instant;
import java.util.List;

@Repository
public interface FiveMinuteBarRepository extends JpaRepository<FiveMinuteBarEntity, FiveMinuteBarId> {

    List<FiveMinuteBarEntity> findBySymbolAndBarStartBetweenOrderByBarStartAsc(
            String symbol, Instant from, Instant to);

    List<FiveMinuteBarEntity> findBySymbolOrderByBarStartDesc(String symbol);

    @Modifying
    @Query(value = "DELETE FROM five_minute_bar WHERE created_at < :cutoff LIMIT :batchSize", nativeQuery = true)
    int deleteBatchByCreatedAtBefore(@Param("cutoff") Instant cutoff, @Param("batchSize") int batchSize);
}