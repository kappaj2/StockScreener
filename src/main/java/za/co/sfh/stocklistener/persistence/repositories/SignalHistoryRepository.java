package za.co.sfh.stocklistener.persistence.repositories;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import za.co.sfh.stocklistener.persistence.entities.SignalHistoryEntity;

import java.util.List;

@Repository
public interface SignalHistoryRepository extends JpaRepository<SignalHistoryEntity, Long> {

    List<SignalHistoryEntity> findBySymbolOrderByCreatedAtDesc(String symbol);

    List<SignalHistoryEntity> findBySignalIdOrderByCreatedAtDesc(String signalId);

    List<SignalHistoryEntity> findAllByOrderByCreatedAtDesc(Pageable pageable);
}
