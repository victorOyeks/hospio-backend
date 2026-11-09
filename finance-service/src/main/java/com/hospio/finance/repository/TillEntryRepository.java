package com.hospio.finance.repository;

import com.hospio.finance.entity.TillEntry;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TillEntryRepository extends JpaRepository<TillEntry, String> {
    List<TillEntry> findByTillIdOrderByCreatedAt(String tillId);

    Optional<TillEntry> findByPaymentId(String paymentId);

    List<TillEntry> findByHotelIdAndCreatedAtBetweenOrderByCreatedAtDesc(String hotelId, java.time.Instant from, java.time.Instant to);

    Optional<TillEntry> findFirstByKindAndRef(TillEntry.Kind kind, String ref);

    @org.springframework.data.jpa.repository.Query("select e.tillId, count(e) from TillEntry e where e.tillId in :ids group by e.tillId")
    List<Object[]> countByTill(@org.springframework.data.repository.query.Param("ids") java.util.Collection<String> ids);
}
