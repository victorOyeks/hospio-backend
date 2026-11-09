package com.hospio.platform.repository;

import com.hospio.platform.entity.SupportTicket;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface SupportTicketRepository extends JpaRepository<SupportTicket, String>, JpaSpecificationExecutor<SupportTicket> {
    List<SupportTicket> findByStatusOrderByCreatedAtDesc(String status);

    List<SupportTicket> findByBusinessIdOrderByCreatedAtDesc(String businessId);

    List<SupportTicket> findByBusinessIdAndStatusInOrderByCreatedAtDesc(String businessId, Collection<String> statuses);

    long countByStatusIn(Collection<String> statuses);

    long countByBusinessIdAndStatusIn(String businessId, Collection<String> statuses);

    List<SupportTicket> findByPriorityAndStatusIn(String priority, Collection<String> statuses);
}
