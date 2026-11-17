package com.hospio.platform.repository;

import com.hospio.platform.entity.DeviceCatalogueItem;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DeviceCatalogueRepository extends JpaRepository<DeviceCatalogueItem, String> {
    List<DeviceCatalogueItem> findAllByOrderBySortOrderAscNameAsc();
}
