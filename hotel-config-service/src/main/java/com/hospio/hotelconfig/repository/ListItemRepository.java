package com.hospio.hotelconfig.repository;

import com.hospio.hotelconfig.entity.ListItem;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ListItemRepository extends JpaRepository<ListItem, String> {
    List<ListItem> findByHotelIdAndListNameOrderBySortOrderAscLabelAsc(String hotelId, String listName);

    List<ListItem> findByHotelIdAndListNameAndActiveTrueOrderBySortOrderAscLabelAsc(String hotelId, String listName);
}
