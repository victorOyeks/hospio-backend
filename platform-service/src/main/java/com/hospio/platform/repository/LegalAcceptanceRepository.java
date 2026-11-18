package com.hospio.platform.repository;

import com.hospio.platform.entity.LegalAcceptance;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LegalAcceptanceRepository extends JpaRepository<LegalAcceptance, String> {

    List<LegalAcceptance> findByUserIdAndEventOrderByCreatedAtDesc(String userId, String event);

    List<LegalAcceptance> findByBusinessIdOrderByCreatedAtDesc(String businessId);

    boolean existsByUserIdAndDocKeyAndVersionAndEvent(String userId, String docKey, String version, String event);

    boolean existsByUserIdAndDocKeyAndEvent(String userId, String docKey, String event);
}
