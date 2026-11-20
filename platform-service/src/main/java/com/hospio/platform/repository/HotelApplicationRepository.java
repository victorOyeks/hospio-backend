package com.hospio.platform.repository;

import com.hospio.platform.entity.HotelApplication;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface HotelApplicationRepository extends JpaRepository<HotelApplication, String>, JpaSpecificationExecutor<HotelApplication> {

    /** The open application (not onboarded or declined) with this email, if any. */
    Optional<HotelApplication> findFirstByEmailIgnoreCaseAndStatusNotIn(String email, Collection<String> statuses);

    long countByStatus(String status);

    boolean existsByBusinessId(String businessId);

    /** The row locked for a change, so two admins can't move it at once. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from HotelApplication a where a.id = :id")
    Optional<HotelApplication> findForUpdate(@Param("id") String id);
}
