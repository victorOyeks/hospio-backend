package com.hospio.folio.repository;

import com.hospio.folio.entity.OrganizationPayment;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrganizationPaymentRepository extends JpaRepository<OrganizationPayment, String> {
    List<OrganizationPayment> findByOrganizationIdOrderByReceivedAtDesc(String organizationId);
}
