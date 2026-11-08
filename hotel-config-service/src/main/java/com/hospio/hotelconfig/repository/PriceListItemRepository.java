package com.hospio.hotelconfig.repository;

import com.hospio.hotelconfig.entity.PriceListItem;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PriceListItemRepository extends JpaRepository<PriceListItem, String> {
    List<PriceListItem> findByPriceListIdOrderByName(String priceListId);

    List<PriceListItem> findByPriceListIdAndActiveTrueOrderByName(String priceListId);
}
