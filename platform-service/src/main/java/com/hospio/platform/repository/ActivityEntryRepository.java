package com.hospio.platform.repository;

import com.hospio.platform.entity.ActivityEntry;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ActivityEntryRepository extends JpaRepository<ActivityEntry, String> {
    Optional<ActivityEntry> findByEventId(String eventId);

    org.springframework.data.domain.Page<ActivityEntry> findByHotelIdOrderByOccurredAtDesc(String hotelId, org.springframework.data.domain.Pageable pageable);

    org.springframework.data.domain.Page<ActivityEntry> findByBusinessIdOrderByOccurredAtDesc(String businessId, org.springframework.data.domain.Pageable pageable);

    org.springframework.data.domain.Page<ActivityEntry> findByBusinessIdAndActorNameStartingWithOrderByOccurredAtDesc(String businessId, String actorPrefix, org.springframework.data.domain.Pageable pageable);

    long countByBusinessIdAndOccurredAtAfter(String businessId, java.time.Instant since);
}
