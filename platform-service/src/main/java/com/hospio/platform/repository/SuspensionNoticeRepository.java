package com.hospio.platform.repository;

import com.hospio.platform.entity.SuspensionNotice;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SuspensionNoticeRepository extends JpaRepository<SuspensionNotice, String> {

    Optional<SuspensionNotice> findFirstByBusinessIdOrderByCreatedAtDesc(String businessId);

    List<SuspensionNotice> findByBusinessIdOrderByCreatedAtDesc(String businessId);
}
