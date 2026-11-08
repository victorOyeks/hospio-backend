package com.hospio.messaging.repository;

import com.hospio.messaging.entity.OutboundMessage;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OutboundMessageRepository extends JpaRepository<OutboundMessage, String> {
    List<OutboundMessage> findTop50ByStatusAndNextAttemptAtBeforeOrderByNextAttemptAt(OutboundMessage.Status status, java.time.Instant before);

    Optional<OutboundMessage> findByReference(String reference);
}
