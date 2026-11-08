package com.hospio.platform.repository;

import com.hospio.platform.entity.SupportTicket;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SupportTicketRepository extends JpaRepository<SupportTicket, String> {
    List<SupportTicket> findByStatusOrderByCreatedAtDesc(String status);

    List<SupportTicket> findByBusinessIdOrderByCreatedAtDesc(String businessId);
}
