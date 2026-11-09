package com.hospio.platform.repository;

import com.hospio.platform.entity.PlatformSetting;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PlatformSettingRepository extends JpaRepository<PlatformSetting, String> {
    List<PlatformSetting> findByGroupName(String groupName);

    Optional<PlatformSetting> findByGroupNameAndSettingKey(String groupName, String settingKey);
}
