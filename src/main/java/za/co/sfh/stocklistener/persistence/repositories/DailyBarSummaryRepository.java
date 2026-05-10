package za.co.sfh.stocklistener.persistence.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import za.co.sfh.stocklistener.persistence.entities.DailyBarSummaryEntity;
import za.co.sfh.stocklistener.persistence.entities.DailyBarSummaryId;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface DailyBarSummaryRepository extends JpaRepository<DailyBarSummaryEntity, DailyBarSummaryId> {

    List<DailyBarSummaryEntity> findByTradeDateOrderBySymbolAsc(LocalDate tradeDate);

    List<DailyBarSummaryEntity> findBySymbolOrderByTradeDateDesc(String symbol);

    List<DailyBarSummaryEntity> findBySymbolAndTradeDateBetweenOrderByTradeDateAsc(
            String symbol, LocalDate fromDate, LocalDate toDate);

    @Query(value = FIND_DISTINCT_SYMBOLS, nativeQuery = true)
    List<String> findDistinctSymbolBetweenDates(final LocalDate fromDate, final LocalDate toDate);

    String FIND_DISTINCT_SYMBOLS = """             
            SELECT distinct(symbol)
            FROM daily_bar_summary
            WHERE trade_date >= :fromDate
            AND trade_date <= :toDate;
            """;
}
