package com.hospio.fnb.kafka;

import com.hospio.common.kafka.KafkaTopics;
import com.hospio.common.kafka.events.ApprovalDecidedEvent;
import com.hospio.common.kafka.events.PaymentApprovedEvent;
import com.hospio.common.tenancy.TenantBinder;
import com.hospio.common.tenancy.TenantContext;
import com.hospio.fnb.service.MenuService;
import com.hospio.fnb.service.OrderService;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** A manager decided on a price change; a payment for an order was approved. */
@Component
@RequiredArgsConstructor
public class FnbEventListener {

    private final MenuService menuService;
    private final OrderService orderService;
    private final com.hospio.fnb.service.SaleRequestService saleRequests;
    private final TenantBinder tenant;

    @Transactional
    @KafkaListener(topics = KafkaTopics.APPROVAL_DECIDED)
    public void onApprovalDecided(ApprovalDecidedEvent e) {
        if (!"FNB".equals(e.getSourceModule())) {
            return;
        }
        tenant.bind(e);
        try {
            menuService.applyApprovedPriceChange(e.getApprovalId(), e.getDecision());
            // A void or discount asked from the floor (SaleRequestService): applied, or declined, and the requester told.
            saleRequests.decided(e.getApprovalId(), e.getDecision(), e.getDecidedBy(), e.getDecidedByName(), e.getNote());
        } finally {
            TenantContext.clear();
        }
    }

    /** A payment carrying an order id marks that order paid (closed when paid in full). Once per payment, however often it is delivered. */
    @Transactional
    @KafkaListener(topics = KafkaTopics.PAYMENT_APPROVED)
    public void onPaymentApproved(PaymentApprovedEvent e) {
        if (e.getOrderId() == null || e.getOrderId().isBlank()) {
            return;
        }
        tenant.bind(e);
        try {
            orderService.applyPayment(e.getOrderId(), e.getPaymentId(), e.getMethod(), e.getAmount(), e.getProviderRef(), e.getPartId(), e.getTipAmount());
        } finally {
            TenantContext.clear();
        }
    }
}
