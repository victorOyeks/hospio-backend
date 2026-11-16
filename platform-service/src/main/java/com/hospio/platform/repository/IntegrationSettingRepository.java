package com.hospio.platform.repository;

import com.hospio.platform.entity.IntegrationSetting;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IntegrationSettingRepository extends JpaRepository<IntegrationSetting, String> {

    Optional<IntegrationSetting> findByProvider(String provider);
}
