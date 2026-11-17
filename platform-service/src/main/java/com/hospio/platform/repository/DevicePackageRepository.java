package com.hospio.platform.repository;

import com.hospio.platform.entity.DevicePackage;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DevicePackageRepository extends JpaRepository<DevicePackage, String> {

    /** The hotel's current package (anything not cancelled), newest first. */
    Optional<DevicePackage> findFirstByHotelIdAndStatusNotOrderByCreatedAtDesc(String hotelId, String status);

    List<DevicePackage> findByBusinessIdAndStatusNotOrderByHotelNameAsc(String businessId, String status);

    List<DevicePackage> findByStatusInOrderByUpdatedAtDesc(Collection<String> statuses);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from DevicePackage p where p.id = :id")
    Optional<DevicePackage> lockById(@Param("id") String id);
}
