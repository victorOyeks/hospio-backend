package com.hospio.platform.repository;

import com.hospio.platform.entity.Commission;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface CommissionRepository extends JpaRepository<Commission, String>, JpaSpecificationExecutor<Commission> {
    List<Commission> findByMarketerIdOrderByMonthDesc(String marketerId);

    List<Commission> findByStatusOrderByMonthDesc(String status);

    List<Commission> findByProfileIdOrderByMonthDesc(String profileId);

    List<Commission> findByProfileIdAndMonthOrderByCreatedAtAsc(String profileId, String month);

    Optional<Commission> findByMarketerIdAndMonth(String marketerId, String month);

    Optional<Commission> findBySourceAndSourceRef(String source, String sourceRef);

    List<Commission> findBySourceIdOrderByCreatedAtAsc(String sourceId);

    /** Lines not on a statement yet, up to a month (a late line from an earlier month goes on the next statement). */
    List<Commission> findByProfileIdAndStatementIdIsNullAndSourceIsNotNullAndMonthLessThanEqualOrderByEarnedOnAscCreatedAtAsc(String profileId, String month);

    @org.springframework.data.jpa.repository.Query("select distinct c.profileId from Commission c where c.statementId is null and c.source is not null and c.month <= :month")
    List<String> profilesWithOpenLines(@org.springframework.data.repository.query.Param("month") String month);

    List<Commission> findByStatementIdOrderByEarnedOnAscCreatedAtAsc(String statementId);

    List<Commission> findByProfileIdAndStatementIdIsNullAndSourceIsNotNull(String profileId);
}
