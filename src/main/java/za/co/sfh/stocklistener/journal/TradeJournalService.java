package za.co.sfh.stocklistener.journal;

import za.co.sfh.stocklistener.persistence.entities.TradeJournalEntity;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface TradeJournalService {

    TradeJournalEntity save(TradeJournalEntity entry);

    Optional<TradeJournalEntity> findById(Long id);

    List<TradeJournalEntity> findAll();

    List<TradeJournalEntity> findByDateRange(LocalDate from, LocalDate to);

    List<TradeJournalEntity> findByTicker(String ticker);

    void delete(Long id);
}
