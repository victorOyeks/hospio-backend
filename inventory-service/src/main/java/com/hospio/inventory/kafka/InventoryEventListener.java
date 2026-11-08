package com.hospio.inventory.kafka;

import com.hospio.common.kafka.KafkaTopics;
import com.hospio.common.kafka.events.ApprovalDecidedEvent;
import com.hospio.common.tenancy.TenantBinder;
import com.hospio.common.tenancy.TenantContext;
import com.hospio.inventory.service.InventoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** The owner decided on a purchase order over the manager's limit. */
@Component
@RequiredArgsConstructor
public class InventoryEventListener {

    private final InventoryService service;
    private final TenantBinder tenant;

    @Transactional
    @KafkaListener(topics = KafkaTopics.APPROVAL_DECIDED)
    public void onApprovalDecided(ApprovalDecidedEvent e) {
        if (!"INVENTORY".equals(e.getSourceModule())) {
            return;
        }
        tenant.bind(e);
        try {
            service.onOrderDecided(e.getApprovalId(), e.getDecision());
        } finally {
            TenantContext.clear();
        }
    }
}
