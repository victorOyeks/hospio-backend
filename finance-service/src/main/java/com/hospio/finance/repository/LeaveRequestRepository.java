package com.hospio.finance.repository;

import com.hospio.finance.entity.LeaveRequest;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LeaveRequestRepository extends JpaRepository<LeaveRequest, String> {
    List<LeaveRequest> findByHotelIdAndStatusOrderByCreatedAt(String hotelId, LeaveRequest.Status status);

    List<LeaveRequest> findByUserIdOrderByFromDateDesc(String userId);
}
