package com.hospio.folio.repository;

import com.hospio.folio.entity.Organization;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrganizationRepository extends JpaRepository<Organization, String> {
    List<Organization> findByHotelIdOrderByName(String hotelId);

    java.util.Optional<Organization> findByCreditApprovalId(String creditApprovalId);
}
