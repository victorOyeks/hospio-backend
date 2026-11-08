package com.hospio.maintenance.repository;

import com.hospio.maintenance.entity.RepairUpdate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RepairUpdateRepository extends JpaRepository<RepairUpdate, String> {
    List<RepairUpdate> findByRepairIdOrderByCreatedAt(String repairId);
}
