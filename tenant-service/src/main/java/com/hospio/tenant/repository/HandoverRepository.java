package com.hospio.tenant.repository;

import com.hospio.tenant.entity.Handover;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface HandoverRepository extends JpaRepository<Handover, String> {
    Optional<Handover> findByBusinessId(String businessId);
}
