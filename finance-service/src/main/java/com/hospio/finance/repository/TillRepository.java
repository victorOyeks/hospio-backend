package com.hospio.finance.repository;

import com.hospio.finance.entity.Till;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TillRepository extends JpaRepository<Till, String> {
    Optional<Till> findFirstByHotelIdAndRoleAndStatus(String hotelId, String role, Till.Status status);

    List<Till> findByHotelIdAndStatusOrderByOpenedAt(String hotelId, Till.Status status);

    List<Till> findByHotelIdAndOpenedAtAfterOrderByOpenedAtDesc(String hotelId, java.time.Instant since);

    List<Till> findByHotelIdAndClosedAtBetween(String hotelId, java.time.Instant from, java.time.Instant to);

    /** The till, locked until the transaction ends: entries, close and handover change a till one at a time. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from Till t where t.id = :id")
    Optional<Till> lockById(@Param("id") String id);

    /** The role's open till(s), oldest first, locked: cash lands in a till that can't be closing under it. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from Till t where t.hotelId = :hotelId and t.role = :role and t.status = com.hospio.finance.entity.Till.Status.OPEN order by t.openedAt")
    List<Till> lockOpen(@Param("hotelId") String hotelId, @Param("role") String role);

    /** Locks the till row and reads its status as committed now (not as this transaction first loaded it). */
    @Query(value = "SELECT status FROM finance.tills WHERE id = :id FOR UPDATE", nativeQuery = true)
    Optional<String> lockStatus(@Param("id") String id);
}
