package za.co.sfh.stocklistener.journal;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import za.co.sfh.stocklistener.persistence.entities.TradeJournalEntity;
import za.co.sfh.stocklistener.persistence.repositories.TradeJournalRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class TradeJournalServiceImpl implements TradeJournalService {

    private final TradeJournalRepository repository;

    @Override
    public TradeJournalEntity save(TradeJournalEntity entry) {
        TradeJournalEntity saved = repository.save(entry);
        log.debug("Saved trade journal entry id={} ticker={} date={}", saved.getId(), saved.getTicker(), saved.getTradeDate());
        return saved;
    }

    @Override
    public Optional<TradeJournalEntity> findById(Long id) {
        return repository.findById(id);
    }

    @Override
    public List<TradeJournalEntity> findAll() {
        return repository.findAllByOrderByTradeDateDescCreatedAtDesc();
    }

    @Override
    public List<TradeJournalEntity> findByDateRange(LocalDate from, LocalDate to) {
        return repository.findByTradeDateBetweenOrderByTradeDateDescCreatedAtDesc(from, to);
    }

    @Override
    public List<TradeJournalEntity> findByTicker(String ticker) {
        return repository.findByTickerIgnoreCaseOrderByTradeDateDesc(ticker);
    }

    @Override
    public void delete(Long id) {
        repository.deleteById(id);
        log.debug("Deleted trade journal entry id={}", id);
    }
}
