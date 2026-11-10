package com.hospio.property.kafka;

import com.hospio.common.kafka.KafkaTopics;
import com.hospio.common.kafka.events.ApprovalDecidedEvent;
import com.hospio.common.tenancy.TenantBinder;
import com.hospio.common.tenancy.TenantContext;
import com.hospio.property.service.PropertyService;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** The owner's answer to a manager's room price change (sourceModule PROPERTY, kind ROOM_PRICE). Replays are harmless. */
@Component
@RequiredArgsConstructor
public class PropertyEventListener {

    private final PropertyService propertyService;
    private final TenantBinder tenant;

    @Transactional
    @KafkaListener(topics = KafkaTopics.APPROVAL_DECIDED)
    public void onApprovalDecided(ApprovalDecidedEvent e) {
        if (!PropertyService.PROPERTY.equals(e.getSourceModule()) || !PropertyService.ROOM_PRICE.equals(e.getKind())) {
            return;
        }
        tenant.bind(e);
        try {
            propertyService.applyPriceDecision(e.getApprovalId(), e.getDecision());
        } finally {
            TenantContext.clear();
        }
    }
}
