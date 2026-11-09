package com.hospio.platform.repository;

import com.hospio.platform.entity.PlatformChange;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface PlatformChangeRepository extends JpaRepository<PlatformChange, String>, JpaSpecificationExecutor<PlatformChange> {
    List<PlatformChange> findByBusinessIdOrderByCreatedAtDesc(String businessId);

    List<PlatformChange> findAllByOrderByCreatedAtDesc(Pageable pageable);
}
