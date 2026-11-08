package com.hospio.finance.repository;

import com.hospio.finance.entity.Shift;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ShiftRepository extends JpaRepository<Shift, String> {
    List<Shift> findByHotelIdAndShiftDateBetweenOrderByShiftDateAscStartsAtAsc(String hotelId, java.time.LocalDate from, java.time.LocalDate to);

    List<Shift> findByUserIdAndShiftDateGreaterThanEqualOrderByShiftDateAsc(String userId, java.time.LocalDate from);
}
