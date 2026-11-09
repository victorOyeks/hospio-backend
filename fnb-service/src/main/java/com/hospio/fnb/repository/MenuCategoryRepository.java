package com.hospio.fnb.repository;

import com.hospio.fnb.entity.MenuCategory;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MenuCategoryRepository extends JpaRepository<MenuCategory, String> {
    List<MenuCategory> findByOutletIdOrderBySortOrderAscNameAsc(String outletId);

    Optional<MenuCategory> findByOutletIdAndName(String outletId, String name);
}
