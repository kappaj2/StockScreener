package za.co.sfh.stocklistener.persistence.repositories;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import za.co.sfh.stocklistener.persistence.entities.DailyBarScoreEntity;
import za.co.sfh.stocklistener.persistence.entities.DailyBarScoreId;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface DailyBarScoreRepository extends JpaRepository<DailyBarScoreEntity, DailyBarScoreId> {

    List<DailyBarScoreEntity> findBySymbolOrderByTradeDateDesc(String symbol);

    List<DailyBarScoreEntity> findBySymbolAndTradeDateBetweenOrderByTradeDateDesc(
            String symbol, LocalDate from, LocalDate to);

    @Query("SELECT d FROM DailyBarScoreEntity d WHERE d.tradeDate = :date ORDER BY d.rolling10dScore DESC")
    List<DailyBarScoreEntity> findTopByDate(@Param("date") LocalDate date, Pageable pageable);

    List<DailyBarScoreEntity> findByTradeDateAndRolling10dScoreGreaterThanEqualOrderByRolling10dScoreDesc(
            LocalDate tradeDate, int threshold);

    void deleteByTradeDate(LocalDate tradeDate);
}