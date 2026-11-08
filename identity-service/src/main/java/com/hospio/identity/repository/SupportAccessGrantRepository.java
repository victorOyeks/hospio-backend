package com.hospio.identity.repository;

import com.hospio.identity.entity.SupportAccessGrant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SupportAccessGrantRepository extends JpaRepository<SupportAccessGrant, String> {
    List<SupportAccessGrant> findByAdminIdOrderByCreatedAtDesc(String adminId);

    List<SupportAccessGrant> findByBusinessIdOrderByCreatedAtDesc(String businessId);
}
