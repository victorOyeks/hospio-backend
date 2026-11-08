package com.hospio.fnb.kafka;

import com.hospio.common.kafka.KafkaTopics;
import com.hospio.common.kafka.events.ApprovalDecidedEvent;
import com.hospio.common.tenancy.TenantBinder;
import com.hospio.common.tenancy.TenantContext;
import com.hospio.fnb.service.MenuService;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** A manager decided on a price change. */
@Component
@RequiredArgsConstructor
public class FnbEventListener {

    private final MenuService menuService;
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
        } finally {
            TenantContext.clear();
        }
    }
}
