package com.hospio.maintenance.kafka;

import com.hospio.common.kafka.KafkaTopics;
import com.hospio.common.kafka.events.ApprovalDecidedEvent;
import com.hospio.common.tenancy.TenantBinder;
import com.hospio.common.tenancy.TenantContext;
import com.hospio.maintenance.service.MaintenanceService;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** A manager or the owner decided on a parts request. */
@Component
@RequiredArgsConstructor
public class MaintenanceEventListener {

    private final MaintenanceService service;
    private final TenantBinder tenant;

    @Transactional
    @KafkaListener(topics = KafkaTopics.APPROVAL_DECIDED)
    public void onApprovalDecided(ApprovalDecidedEvent e) {
        if (!"MAINTENANCE".equals(e.getSourceModule())) {
            return;
        }
        tenant.bind(e);
        try {
            service.onPartsDecided(e.getApprovalId(), e.getDecision());
        } finally {
            TenantContext.clear();
        }
    }
}
