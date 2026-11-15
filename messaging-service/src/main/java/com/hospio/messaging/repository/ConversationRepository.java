package com.hospio.messaging.repository;

import com.hospio.messaging.entity.Conversation;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ConversationRepository extends JpaRepository<Conversation, String>, org.springframework.data.jpa.repository.JpaSpecificationExecutor<Conversation> {
    Optional<Conversation> findFirstByHotelIdAndKindAndBookingIdOrderByLastMessageAtDesc(String hotelId, Conversation.Kind kind, String bookingId);

    List<Conversation> findByHotelIdAndKindAndStatusOrderByLastMessageAtDesc(String hotelId, Conversation.Kind kind, Conversation.Status status);

    /**
     * Staff threads the person is part of: started by them, sent to them, or sent to the managers of a hotel they manage.
     * Row-level security keeps it to their business (and to one hotel when the request names one).
     */
    @org.springframework.data.jpa.repository.Query("select c from Conversation c where c.kind = com.hospio.messaging.entity.Conversation.Kind.STAFF"
            + " and c.audience is not null and (c.createdBy = :me or (c.audience = 'PERSON' and c.assignedTo = :me)"
            + " or (c.audience = 'MANAGERS' and c.hotelId in :managerHotels)) order by c.lastMessageAt desc")
    List<Conversation> threadsFor(@org.springframework.data.repository.query.Param("me") String me,
                                  @org.springframework.data.repository.query.Param("managerHotels") java.util.Collection<String> managerHotels);

    /** {@link #threadsFor} a page at a time, newest message first; {@code q} (a lower-cased like pattern; "%" for every one) searches subject, preview and names. */
    @org.springframework.data.jpa.repository.Query(value = "select c from Conversation c where c.kind = com.hospio.messaging.entity.Conversation.Kind.STAFF"
            + " and c.audience is not null and (c.createdBy = :me or (c.audience = 'PERSON' and c.assignedTo = :me)"
            + " or (c.audience = 'MANAGERS' and c.hotelId in :managerHotels))"
            + " and (lower(coalesce(c.subject, '')) like :q escape '\\' or lower(coalesce(c.lastPreview, '')) like :q escape '\\'"
            + " or lower(coalesce(c.createdByName, '')) like :q escape '\\' or lower(coalesce(c.recipientName, '')) like :q escape '\\')",
            countQuery = "select count(c) from Conversation c where c.kind = com.hospio.messaging.entity.Conversation.Kind.STAFF"
            + " and c.audience is not null and (c.createdBy = :me or (c.audience = 'PERSON' and c.assignedTo = :me)"
            + " or (c.audience = 'MANAGERS' and c.hotelId in :managerHotels))"
            + " and (lower(coalesce(c.subject, '')) like :q escape '\\' or lower(coalesce(c.lastPreview, '')) like :q escape '\\'"
            + " or lower(coalesce(c.createdByName, '')) like :q escape '\\' or lower(coalesce(c.recipientName, '')) like :q escape '\\')")
    org.springframework.data.domain.Page<Conversation> threadsPage(@org.springframework.data.repository.query.Param("me") String me,
                                                                   @org.springframework.data.repository.query.Param("managerHotels") java.util.Collection<String> managerHotels,
                                                                   @org.springframework.data.repository.query.Param("q") String q,
                                                                   org.springframework.data.domain.Pageable pageable);

    Optional<Conversation> findFirstByHotelIdAndGuestContactAndStatus(String hotelId, String guestContact, Conversation.Status status);

    /**
     * Retention (docs/retention.md): up to {@code batch} conversations (staff Inbox threads and guest chats) with no message since
     * {@code cutoff}, and their messages, in one statement so a chat is never half deleted. Returns the conversations deleted.
     */
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.transaction.annotation.Transactional
    @org.springframework.data.jpa.repository.Query(value = "WITH old AS (SELECT id FROM messaging.conversations "
            + "WHERE COALESCE(last_message_at, created_at) < :cutoff LIMIT :batch), "
            + "gone AS (DELETE FROM messaging.messages WHERE conversation_id IN (SELECT id FROM old)) "
            + "DELETE FROM messaging.conversations WHERE id IN (SELECT id FROM old)", nativeQuery = true)
    int deleteQuietSince(@org.springframework.data.repository.query.Param("cutoff") java.time.Instant cutoff,
                         @org.springframework.data.repository.query.Param("batch") int batch);
}
