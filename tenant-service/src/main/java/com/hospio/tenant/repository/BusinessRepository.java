package com.hospio.tenant.repository;

import com.hospio.tenant.entity.Business;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface BusinessRepository extends JpaRepository<Business, String>, JpaSpecificationExecutor<Business> {
    List<Business> findByStageOrderByName(Business.Stage stage);

    List<Business> findAllByOrderByName();
}
