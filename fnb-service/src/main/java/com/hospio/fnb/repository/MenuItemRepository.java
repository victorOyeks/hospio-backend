package com.hospio.fnb.repository;

import com.hospio.fnb.entity.MenuItem;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MenuItemRepository extends JpaRepository<MenuItem, String> {
    List<MenuItem> findByOutletIdAndActiveTrueOrderByCategoryAscSortOrderAscNameAsc(String outletId);

    List<MenuItem> findByHotelIdAndActiveTrueOrderByCategoryAscNameAsc(String hotelId);
}
