package com.hospio.fnb.repository;

import com.hospio.fnb.entity.HappyHour;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface HappyHourRepository extends JpaRepository<HappyHour, String> {
    List<HappyHour> findByOutletIdAndActiveTrue(String outletId);
}
