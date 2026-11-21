package com.hospio.finance.service;

import com.hospio.common.kafka.events.DeliveryReceivedEvent;
import com.hospio.common.tenancy.TenantContext;
import com.hospio.common.util.Money;
import com.hospio.common.util.PaymentTerms;
import com.hospio.finance.entity.Bill;
import com.hospio.finance.repository.BillRepository;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Supplier bills from deliveries (docs/inventory-stores.md, Supplier bills): a delivery received in Inventory becomes one unpaid bill here,
 * to the supplier, for the value received (good units times cost; damaged units are never in it), due on the received date plus the
 * supplier's payment terms (the same day when there are none), under Stock purchases. One bill per delivery: a replayed event finds the
 * bill and does nothing, and a delivery worth nothing makes none. Deliveries received before this existed (their events carry no
 * received date) are left unbilled. Nothing is ever paid here; Finance pays it like any other bill.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SupplierBills {

    private final BillRepository bills;
    private final ExpenseCategories categories;

    /** The bill made for the delivery, or empty when there is nothing to bill or it was already made. */
    @Transactional
    public Optional<Bill> fromDelivery(DeliveryReceivedEvent e) {
        if (e.getDeliveryId() == null || !Money.isPositive(e.getTotal())) {
            return Optional.empty();
        }
        if (e.getReceivedOn() == null) {
            // Published before supplier bills existed (still on the topic when finance first subscribed): those stay unbilled.
            log.info("Delivery {} was received before supplier bills; no bill made", e.getDeliveryId());
            return Optional.empty();
        }
        if (bills.existsByDeliveryId(e.getDeliveryId())) {
            log.info("Delivery {} already has its bill; replay ignored", e.getDeliveryId());
            return Optional.empty();
        }
        String hotel = TenantContext.requireHotel();
        LocalDate received = receivedOn(e);
        String payee = e.getSupplierName() == null || e.getSupplierName().isBlank() ? "Supplier" : cut(e.getSupplierName().trim(), 150);
        Bill b = bills.save(Bill.builder().payee(payee).category(categories.ensure(hotel, ExpenseCategories.STOCK_PURCHASES))
                .amount(Money.of(e.getTotal())).dueDate(PaymentTerms.due(received, e.getTermsDays())).repeats(Bill.Repeats.NONE)
                .note(note(e)).createdByName(e.getActorName() == null ? null : cut(e.getActorName(), 150)).supplierId(e.getSupplierId())
                .deliveryId(e.getDeliveryId()).purchaseOrderId(e.getPurchaseOrderId())
                .invoiceNumber(e.getInvoiceNumber() == null || e.getInvoiceNumber().isBlank() ? null : cut(e.getInvoiceNumber().trim(), 60)).build());
        log.info("Bill {} of {} for delivery {} from {}, due {}", b.getId(), b.getAmount(), e.getDeliveryId(), payee, b.getDueDate());
        return Optional.of(b);
    }

    /** The short reference a delivery goes by on its bill: the first eight characters of its id, upper case. */
    public static String deliveryRef(String deliveryId) {
        String s = deliveryId.replace("-", "");
        return s.substring(0, Math.min(8, s.length())).toUpperCase(java.util.Locale.ROOT);
    }

    /** "Delivery 3F9A2C1B · PO-2026-00007 · invoice INV-77 · 14 days", leaving out what the delivery doesn't have. */
    static String note(DeliveryReceivedEvent e) {
        List<String> bits = new ArrayList<>();
        bits.add("Delivery " + deliveryRef(e.getDeliveryId()));
        if (e.getPurchaseOrderNumber() != null) {
            bits.add(e.getPurchaseOrderNumber());
        }
        if (e.getInvoiceNumber() != null && !e.getInvoiceNumber().isBlank()) {
            bits.add("invoice " + e.getInvoiceNumber().trim());
        }
        if (e.getPaymentTerms() != null && !e.getPaymentTerms().isBlank()) {
            bits.add(e.getPaymentTerms().trim());
        }
        return cut(String.join(" · ", bits), 500);
    }

    private static LocalDate receivedOn(DeliveryReceivedEvent e) {
        if (e.getReceivedOn() != null) {
            try {
                return LocalDate.parse(e.getReceivedOn());
            } catch (DateTimeParseException ignored) {
                // fall through to when it happened
            }
        }
        return e.getOccurredAt() != null ? LocalDate.ofInstant(e.getOccurredAt(), MoneyService.LAGOS) : LocalDate.now(MoneyService.LAGOS);
    }

    private static String cut(String s, int max) {
        return s.length() > max ? s.substring(0, max) : s;
    }
}
