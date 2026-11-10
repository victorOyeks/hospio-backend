package com.hospio.messaging.repository;

import com.hospio.messaging.entity.DeliveryRecord;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface DeliveryRecordRepository extends JpaRepository<DeliveryRecord, String>, JpaSpecificationExecutor<DeliveryRecord> {

    Optional<DeliveryRecord> findByEventId(String eventId);

    List<DeliveryRecord> findTop100ByStatusAndCreatedAtBefore(DeliveryRecord.Status status, Instant before);
}
