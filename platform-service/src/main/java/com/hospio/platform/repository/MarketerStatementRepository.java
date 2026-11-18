package com.hospio.platform.repository;

import com.hospio.platform.entity.MarketerStatement;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface MarketerStatementRepository extends JpaRepository<MarketerStatement, String>, JpaSpecificationExecutor<MarketerStatement> {

    Optional<MarketerStatement> findByProfileIdAndMonth(String profileId, String month);

    List<MarketerStatement> findByProfileIdOrderByMonthDesc(String profileId);

    /** Statements under the minimum not yet carried into a later one. */
    List<MarketerStatement> findByProfileIdAndStatusAndCarriedToIdIsNullAndMonthLessThan(String profileId, String status, String month);

    /** Every statement under the minimum not yet carried into a later one, whatever its month (an ENDED marketer's final statement). */
    List<MarketerStatement> findByProfileIdAndStatusAndCarriedToIdIsNull(String profileId, String status);

    @org.springframework.data.jpa.repository.Query("select distinct s.profileId from MarketerStatement s where s.status = 'CARRIED_OVER' and s.carriedToId is null and s.month < :month")
    List<String> profilesWithCarry(@org.springframework.data.repository.query.Param("month") String month);
}
