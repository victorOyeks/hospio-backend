package com.hospio.messaging.repository;

import com.hospio.messaging.entity.Conversation;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ConversationRepository extends JpaRepository<Conversation, String> {
    List<Conversation> findByHotelIdAndKindAndStatusOrderByLastMessageAtDesc(String hotelId, Conversation.Kind kind, Conversation.Status status);

    Optional<Conversation> findFirstByHotelIdAndGuestContactAndStatus(String hotelId, String guestContact, Conversation.Status status);
}
