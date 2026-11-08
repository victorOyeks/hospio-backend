package com.hospio.hotelconfig.repository;

import com.hospio.hotelconfig.entity.PriceList;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PriceListRepository extends JpaRepository<PriceList, String> {
    List<PriceList> findByHotelIdOrderByName(String hotelId);

    Optional<PriceList> findByHotelIdAndKind(String hotelId, String kind);
}
