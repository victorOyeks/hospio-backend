package com.hospio.hotelconfig.repository;

import com.hospio.hotelconfig.entity.Setting;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SettingRepository extends JpaRepository<Setting, String> {
    List<Setting> findByHotelIdAndSection(String hotelId, String section);

    Optional<Setting> findByHotelIdAndSectionAndKey(String hotelId, String section, String key);

    List<Setting> findByHotelId(String hotelId);
}
