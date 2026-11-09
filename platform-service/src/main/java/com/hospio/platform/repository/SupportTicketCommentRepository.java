package com.hospio.platform.repository;

import com.hospio.platform.entity.SupportTicketComment;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SupportTicketCommentRepository extends JpaRepository<SupportTicketComment, String> {
    List<SupportTicketComment> findByTicketIdOrderByCreatedAtAsc(String ticketId);
}
