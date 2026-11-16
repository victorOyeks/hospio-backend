package com.hospio.payment.pos;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface HotelPosConfigRepository extends JpaRepository<HotelPosConfig, String> {

    Optional<HotelPosConfig> findByHotelId(String hotelId);

    List<HotelPosConfig> findByProvider(String provider);
}
