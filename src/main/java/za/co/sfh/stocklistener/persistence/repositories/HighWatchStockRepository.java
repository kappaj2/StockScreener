package za.co.sfh.stocklistener.persistence.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import za.co.sfh.stocklistener.persistence.entities.HighWatchStockEntity;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface HighWatchStockRepository extends JpaRepository<HighWatchStockEntity, String> {

    List<HighWatchStockEntity> findBySourceList(String sourceList);

    @Query("SELECT h FROM HighWatchStockEntity h WHERE h.validFrom <= :now AND (h.validTo IS NULL OR h.validTo > :now)")
    List<HighWatchStockEntity> findActive(@Param("now") LocalDateTime now);

    @Modifying
    @Query("UPDATE HighWatchStockEntity h SET h.validFrom = :validFrom, h.validTo = :validTo WHERE h.symbol = :symbol")
    int updateValidDates(@Param("symbol") String symbol,
                         @Param("validFrom") LocalDateTime validFrom,
                         @Param("validTo") LocalDateTime validTo);
}