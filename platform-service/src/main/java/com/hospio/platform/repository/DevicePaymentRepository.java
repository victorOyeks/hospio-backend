package com.hospio.platform.repository;

import com.hospio.platform.entity.DevicePayment;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DevicePaymentRepository extends JpaRepository<DevicePayment, String> {
    List<DevicePayment> findByPackageIdOrderByPaidOnDescCreatedAtDesc(String packageId);

    boolean existsByPackageIdAndSourceAndReference(String packageId, String source, String reference);
}
