package com.hospio.platform.repository;

import com.hospio.platform.entity.Marketer;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MarketerRepository extends JpaRepository<Marketer, String> {
    List<Marketer> findAllByOrderByCreatedAtDesc();

    List<Marketer> findByUserId(String userId);

    List<Marketer> findByBusinessId(String businessId);
}
