package com.hospio.folio.repository;

import com.hospio.folio.entity.Invoice;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InvoiceRepository extends JpaRepository<Invoice, String> {
    List<Invoice> findByOrganizationIdOrderByDueDateDesc(String organizationId);

    long countByHotelId(String hotelId);
}
