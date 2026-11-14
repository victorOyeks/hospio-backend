package com.hospio.finance.repository;

import com.hospio.finance.entity.Shift;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ShiftRepository extends JpaRepository<Shift, String> {
    List<Shift> findByHotelIdAndShiftDateBetweenOrderByShiftDateAscStartsAtAsc(String hotelId, java.time.LocalDate from, java.time.LocalDate to);

    List<Shift> findByUserIdAndShiftDateGreaterThanEqualOrderByShiftDateAsc(String userId, java.time.LocalDate from);

    List<Shift> findByUserIdAndShiftDateBetween(String userId, java.time.LocalDate from, java.time.LocalDate to);

    /** Shifts on these days nobody has been told about yet (the not-in check; every hotel, run as the system). */
    @org.springframework.data.jpa.repository.Query("select s from Shift s where s.shiftDate between :from and :to and s.notInNotifiedAt is null "
            + "and s.status <> com.hospio.finance.entity.Shift.Status.CANCELLED")
    List<Shift> notInCandidates(@org.springframework.data.repository.query.Param("from") java.time.LocalDate from,
                                @org.springframework.data.repository.query.Param("to") java.time.LocalDate to);

    /** Marks the shift as told about, once (a second run or instance changes nothing). */
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query("update Shift s set s.notInNotifiedAt = :at where s.id = :id and s.notInNotifiedAt is null")
    int markNotInNotified(@org.springframework.data.repository.query.Param("id") String id, @org.springframework.data.repository.query.Param("at") java.time.Instant at);
}
