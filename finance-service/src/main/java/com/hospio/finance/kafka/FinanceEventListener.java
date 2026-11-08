package com.hospio.finance.kafka;

import com.hospio.common.kafka.KafkaTopics;
import com.hospio.common.kafka.events.ApprovalDecidedEvent;
import com.hospio.common.kafka.events.PaymentApprovedEvent;
import com.hospio.common.tenancy.TenantBinder;
import com.hospio.common.tenancy.TenantContext;
import com.hospio.finance.service.MoneyService;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Every approved payment lands in the ledger (and a cash one in the role's till); approvals settle expenses. */
@Component
@RequiredArgsConstructor
public class FinanceEventListener {

    private final MoneyService money;
    private final TenantBinder tenant;

    @Transactional
    @KafkaListener(topics = KafkaTopics.PAYMENT_APPROVED)
    public void onPaymentApproved(PaymentApprovedEvent e) {
        tenant.bind(e);
        try {
            money.recordPayment(e.getPaymentId(), e.getAmount(), e.getMethod(), e.getRole(), e.getFolioId(), e.getOccurredAt());
        } finally {
            TenantContext.clear();
        }
    }

    @Transactional
    @KafkaListener(topics = KafkaTopics.APPROVAL_DECIDED)
    public void onApprovalDecided(ApprovalDecidedEvent e) {
        if (!"FINANCE".equals(e.getSourceModule())) {
            return;
        }
        tenant.bind(e);
        try {
            money.onExpenseDecided(e.getApprovalId(), e.getDecision(), e.getDecidedBy(), e.getNote());
        } finally {
            TenantContext.clear();
        }
    }
}
