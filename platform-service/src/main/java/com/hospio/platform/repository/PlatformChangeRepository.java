package com.hospio.platform.repository;

import com.hospio.platform.entity.PlatformChange;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PlatformChangeRepository extends JpaRepository<PlatformChange, String> {
    List<PlatformChange> findByBusinessIdOrderByCreatedAtDesc(String businessId);
}
