package com.hospio.platform.repository;

import com.hospio.platform.entity.PartnerApplication;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PartnerApplicationRepository extends JpaRepository<PartnerApplication, String>, JpaSpecificationExecutor<PartnerApplication> {

    boolean existsByEmailIgnoreCaseAndStatus(String email, String status);

    long countByStatus(String status);

    /** The row locked for a decision, so two admins can't both decide it. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from PartnerApplication a where a.id = :id")
    Optional<PartnerApplication> findForDecision(@Param("id") String id);
}
