package com.hospio.housekeeping.repository;

import com.hospio.housekeeping.entity.DamagedLinen;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DamagedLinenRepository extends JpaRepository<DamagedLinen, String> {
    List<DamagedLinen> findByHotelIdOrderByCreatedAtDesc(String hotelId);

    Optional<DamagedLinen> findByApprovalId(String approvalId);
}
