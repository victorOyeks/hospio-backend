package com.hospio.fnb.repository;

import com.hospio.fnb.entity.DiningTable;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DiningTableRepository extends JpaRepository<DiningTable, String> {
    List<DiningTable> findByOutletIdOrderByNumber(String outletId);
}
