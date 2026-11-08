package com.hospio.maintenance.repository;

import com.hospio.maintenance.entity.Repair;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RepairRepository extends JpaRepository<Repair, String> {
    List<Repair> findByHotelIdAndStatusInOrderByPriorityDescCreatedAtAsc(String hotelId, java.util.Collection<Repair.Status> statuses);

    List<Repair> findByHotelIdAndAssignedToAndStatusInOrderByPriorityDescCreatedAtAsc(String hotelId, String assignedTo, java.util.Collection<Repair.Status> statuses);
}
