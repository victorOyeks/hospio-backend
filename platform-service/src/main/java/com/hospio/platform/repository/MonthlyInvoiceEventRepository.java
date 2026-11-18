package com.hospio.platform.repository;

import com.hospio.platform.entity.MonthlyInvoiceEvent;
import java.time.LocalDate;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MonthlyInvoiceEventRepository extends JpaRepository<MonthlyInvoiceEvent, String> {

    List<MonthlyInvoiceEvent> findByInvoiceIdOrderByCreatedAtAsc(String invoiceId);

    boolean existsByInvoiceIdAndKindAndOnDate(String invoiceId, String kind, LocalDate onDate);
}
