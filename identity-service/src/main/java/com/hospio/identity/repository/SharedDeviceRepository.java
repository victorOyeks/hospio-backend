package com.hospio.identity.repository;

import com.hospio.identity.entity.SharedDevice;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SharedDeviceRepository extends JpaRepository<SharedDevice, String> {
    Optional<SharedDevice> findByTokenHash(String tokenHash);

    List<SharedDevice> findByHotelIdAndRevokedAtIsNullOrderByNameAsc(String hotelId);
}
