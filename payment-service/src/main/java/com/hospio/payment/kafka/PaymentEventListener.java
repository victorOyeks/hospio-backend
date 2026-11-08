package com.hospio.payment.kafka;

import com.hospio.common.kafka.KafkaTopics;
import com.hospio.common.kafka.events.ApprovalDecidedEvent;
import com.hospio.common.tenancy.TenantBinder;
import com.hospio.common.tenancy.TenantContext;
import com.hospio.payment.service.RefundService;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** The owner decided on a refund over the manager's limit. */
@Component
@RequiredArgsConstructor
public class PaymentEventListener {

    private final RefundService refunds;
    private final TenantBinder tenant;

    @Transactional
    @KafkaListener(topics = KafkaTopics.APPROVAL_DECIDED)
    public void onApprovalDecided(ApprovalDecidedEvent e) {
        if (!"PAYMENT".equals(e.getSourceModule())) {
            return;
        }
        tenant.bind(e);
        try {
            refunds.onOwnerDecision(e.getApprovalId(), e.getDecision(), e.getDecidedBy(), e.getDecidedByName());
        } finally {
            TenantContext.clear();
        }
    }
}
