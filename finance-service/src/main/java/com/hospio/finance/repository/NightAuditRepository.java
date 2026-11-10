package com.hospio.finance.repository;

import com.hospio.finance.entity.NightAudit;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NightAuditRepository extends JpaRepository<NightAudit, String>, org.springframework.data.jpa.repository.JpaSpecificationExecutor<NightAudit> {
    Optional<NightAudit> findByHotelIdAndBusinessDate(String hotelId, java.time.LocalDate date);

    List<NightAudit> findByHotelIdOrderByBusinessDateDesc(String hotelId);
}
