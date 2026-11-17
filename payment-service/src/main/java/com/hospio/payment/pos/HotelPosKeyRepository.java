package com.hospio.payment.pos;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface HotelPosKeyRepository extends JpaRepository<HotelPosKey, String> {

    List<HotelPosKey> findByHotelIdOrderByProvider(String hotelId);

    Optional<HotelPosKey> findByHotelIdAndProvider(String hotelId, String provider);

    List<HotelPosKey> findByProvider(String provider);

    List<HotelPosKey> findAll();
}
