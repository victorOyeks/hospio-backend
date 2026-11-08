package com.hospio.tenant.repository;

import com.hospio.tenant.entity.SetupTemplate;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SetupTemplateRepository extends JpaRepository<SetupTemplate, String> {
    List<SetupTemplate> findByActiveTrueOrderByName();
}
