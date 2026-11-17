package com.hospio.platform.repository;

import com.hospio.platform.entity.DevicePackageItem;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DevicePackageItemRepository extends JpaRepository<DevicePackageItem, String> {
    List<DevicePackageItem> findByPackageIdOrderByCreatedAtAsc(String packageId);

    void deleteByPackageId(String packageId);
}
