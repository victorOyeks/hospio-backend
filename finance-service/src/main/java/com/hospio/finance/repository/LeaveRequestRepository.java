package com.hospio.finance.repository;

import com.hospio.finance.entity.LeaveRequest;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LeaveRequestRepository extends JpaRepository<LeaveRequest, String> {
    List<LeaveRequest> findByHotelIdAndStatusOrderByCreatedAt(String hotelId, LeaveRequest.Status status);

    List<LeaveRequest> findByUserIdOrderByFromDateDesc(String userId);

    /** Approved leave at the hotel that touches the dates. */
    @org.springframework.data.jpa.repository.Query("select l from LeaveRequest l where l.hotelId = :hotelId and l.status = com.hospio.finance.entity.LeaveRequest.Status.APPROVED "
            + "and l.fromDate <= :to and l.toDate >= :from")
    List<LeaveRequest> approvedBetween(@org.springframework.data.repository.query.Param("hotelId") String hotelId,
                                       @org.springframework.data.repository.query.Param("from") java.time.LocalDate from,
                                       @org.springframework.data.repository.query.Param("to") java.time.LocalDate to);
}
