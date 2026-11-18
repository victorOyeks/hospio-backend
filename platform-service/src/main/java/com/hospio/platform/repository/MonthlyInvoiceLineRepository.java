package com.hospio.platform.repository;

import com.hospio.platform.entity.MonthlyInvoiceLine;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MonthlyInvoiceLineRepository extends JpaRepository<MonthlyInvoiceLine, String> {

    List<MonthlyInvoiceLine> findByInvoiceIdOrderBySortOrderAscCreatedAtAsc(String invoiceId);
}
