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

    /**
     * Unread messages over every staff thread the person is part of (the Inbox badge): from the other side, since they last
     * opened each thread. The threads are {@link ConversationRepository#threadsFor}'s.
     */
    @org.springframework.data.jpa.repository.Query("select count(m) from Message m, Conversation c where m.conversationId = c.id"
            + " and c.kind = com.hospio.messaging.entity.Conversation.Kind.STAFF and c.audience is not null"
            + " and (c.createdBy = :me or (c.audience = 'PERSON' and c.assignedTo = :me) or (c.audience = 'MANAGERS' and c.hotelId in :managerHotels))"
            + " and (m.senderId is null or m.senderId <> :me)"
            + " and m.createdAt > coalesce(case when c.createdBy = :me then c.creatorReadAt else c.recipientReadAt end, :epoch)")
    long unreadInThreads(@org.springframework.data.repository.query.Param("me") String me,
                         @org.springframework.data.repository.query.Param("managerHotels") java.util.Collection<String> managerHotels,
                         @org.springframework.data.repository.query.Param("epoch") java.time.Instant epoch);
}
