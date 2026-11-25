package za.co.sfh.stocklistener.persistence.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import za.co.sfh.stocklistener.persistence.entities.TradeJournalEntity;

import java.time.LocalDate;
import java.util.List;

public interface TradeJournalRepository extends JpaRepository<TradeJournalEntity, Long> {

    List<TradeJournalEntity> findAllByOrderByTradeDateDescCreatedAtDesc();

    List<TradeJournalEntity> findByTradeDateBetweenOrderByTradeDateDescCreatedAtDesc(LocalDate from, LocalDate to);

    List<TradeJournalEntity> findByTickerIgnoreCaseOrderByTradeDateDesc(String ticker);
}
