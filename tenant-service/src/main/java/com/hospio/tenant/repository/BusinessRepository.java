package com.hospio.tenant.repository;

import com.hospio.tenant.entity.Business;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BusinessRepository extends JpaRepository<Business, String> {
    List<Business> findByStageOrderByName(Business.Stage stage);

    List<Business> findAllByOrderByName();
}
