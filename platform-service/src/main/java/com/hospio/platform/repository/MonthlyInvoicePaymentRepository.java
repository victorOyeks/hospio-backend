package com.hospio.platform.repository;

import com.hospio.platform.entity.MonthlyInvoicePayment;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MonthlyInvoicePaymentRepository extends JpaRepository<MonthlyInvoicePayment, String> {

    List<MonthlyInvoicePayment> findByInvoiceIdOrderByCreatedAtAsc(String invoiceId);

    Optional<MonthlyInvoicePayment> findByViaAndReference(String via, String reference);

    Optional<MonthlyInvoicePayment> findByReceiptNumber(String receiptNumber);

    @Query(value = "SELECT COALESCE(MAX(CAST(split_part(receipt_number, '-', 3) AS INTEGER)), 0) FROM platform.monthly_invoice_payments "
            + "WHERE receipt_number LIKE :prefix", nativeQuery = true)
    int lastNumber(@Param("prefix") String prefix);
}
