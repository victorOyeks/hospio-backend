package com.hospio.messaging.repository;

import com.hospio.messaging.entity.Message;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MessageRepository extends JpaRepository<Message, String> {
    List<Message> findByConversationIdOrderByCreatedAt(String conversationId);

    /** Messages from the other side since a person last opened the thread. */
    @org.springframework.data.jpa.repository.Query("select count(m) from Message m where m.conversationId = :conversationId"
            + " and (m.senderId is null or m.senderId <> :me) and m.createdAt > :since")
    long unread(@org.springframework.data.repository.query.Param("conversationId") String conversationId,
                @org.springframework.data.repository.query.Param("me") String me,
                @org.springframework.data.repository.query.Param("since") java.time.Instant since);
}
