package com.hospio.payment.repository;

import com.hospio.payment.entity.PosRoleAssignment;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PosRoleAssignmentRepository extends JpaRepository<PosRoleAssignment, String> {
    Optional<PosRoleAssignment> findByHotelIdAndRole(String hotelId, com.hospio.common.enums.Role role);

    List<PosRoleAssignment> findByHotelId(String hotelId);

    List<PosRoleAssignment> findByTerminalId(String terminalId);
}
