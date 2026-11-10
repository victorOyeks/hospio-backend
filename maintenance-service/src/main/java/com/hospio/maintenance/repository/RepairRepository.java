package com.hospio.maintenance.repository;

import com.hospio.maintenance.entity.Repair;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RepairRepository extends JpaRepository<Repair, String>, org.springframework.data.jpa.repository.JpaSpecificationExecutor<Repair> {
    List<Repair> findByHotelIdAndStatusInOrderByCreatedAtDesc(String hotelId, Collection<Repair.Status> statuses);

    List<Repair> findByHotelIdOrderByCreatedAtDesc(String hotelId);

    List<Repair> findByHotelIdAndRoomIdAndStatusIn(String hotelId, String roomId, Collection<Repair.Status> statuses);

    List<Repair> findByStatusInAndDueAtBeforeAndOverdueNotifiedFalse(Collection<Repair.Status> statuses, Instant before);

    List<Repair> findByHotelIdAndStatusAndDoneAtBetween(String hotelId, Repair.Status status, Instant from, Instant to);

    boolean existsByScheduleIdAndScheduleDue(String scheduleId, LocalDate due);

    @Query(value = "SELECT COALESCE(MAX(CAST(SUBSTRING(number FROM 9) AS INTEGER)), 0) FROM maintenance.repairs "
            + "WHERE hotel_id = :hotelId AND number LIKE :prefix", nativeQuery = true)
    int lastNumber(@Param("hotelId") String hotelId, @Param("prefix") String prefix);

    @Query(value = "SELECT pg_advisory_xact_lock(hashtext(:key))", nativeQuery = true)
    Object lock(@Param("key") String key);
}
