package za.co.sfh.stocklistener.persistence.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import za.co.sfh.stocklistener.persistence.entities.DailyBarSummaryEntity;
import za.co.sfh.stocklistener.persistence.entities.DailyBarSummaryId;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface DailyBarSummaryRepository extends JpaRepository<DailyBarSummaryEntity, DailyBarSummaryId> {

    List<DailyBarSummaryEntity> findByTradeDateOrderBySymbolAsc(LocalDate tradeDate);

    List<DailyBarSummaryEntity> findBySymbolOrderByTradeDateDesc(String symbol);
}
