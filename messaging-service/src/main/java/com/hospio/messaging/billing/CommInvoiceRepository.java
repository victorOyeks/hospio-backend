package com.hospio.messaging.billing;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface CommInvoiceRepository extends JpaRepository<CommInvoice, String>, JpaSpecificationExecutor<CommInvoice> {

    Optional<CommInvoice> findByHotelIdAndMonth(String hotelId, String month);

    Optional<CommInvoice> findByPaymentReference(String reference);

    Optional<CommInvoice> findByNumber(String number);

    List<CommInvoice> findByStatusAndDueOnBefore(CommInvoice.Status status, LocalDate day);

    List<CommInvoice> findByHotelIdAndStatusIn(String hotelId, Collection<CommInvoice.Status> statuses);

    Page<CommInvoice> findByHotelIdOrderByMonthDesc(String hotelId, Pageable page);

    long countByMonth(String month);
}
