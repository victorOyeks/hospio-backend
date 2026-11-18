package com.hospio.inventory.kafka;

import com.hospio.common.kafka.KafkaTopics;
import com.hospio.common.kafka.events.ApprovalDecidedEvent;
import com.hospio.common.kafka.events.HotelCreatedEvent;
import com.hospio.common.kafka.events.OrderSettledEvent;
import com.hospio.common.tenancy.TenantBinder;
import com.hospio.common.tenancy.TenantContext;
import com.hospio.inventory.service.InventoryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** The owner decided on a purchase order over the manager's limit; a kitchen or bar order was settled; a hotel was created. */
@Slf4j
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
            service.onOrderDecided(e.getApprovalId(), e.getDecision(), e.getDecidedByName(), e.getNote());
        } finally {
            TenantContext.clear();
        }
    }

    @Transactional
    @KafkaListener(topics = KafkaTopics.ORDER_SETTLED)
    public void onOrderSettled(OrderSettledEvent e) {
        tenant.bind(e);
        try {
            service.onOrderSettled(e);
        } finally {
            TenantContext.clear();
        }
    }

    /** A new hotel (onboarding's Hotels step or an owner adding one) starts with a Main, Kitchen, Bar and Housekeeping store. */
    @Transactional
    @KafkaListener(topics = KafkaTopics.HOTEL_CREATED)
    public void onHotelCreated(HotelCreatedEvent e) {
        if (e.getHotelId() == null || e.getBusinessId() == null) {
            log.warn("HotelCreated without hotel or business ({} / {}); no default stores", e.getHotelId(), e.getBusinessId());
            return;
        }
        tenant.bind(e);
        try {
            int created = service.createDefaultStores(e.getHotelId());
            log.info("Default stores for hotel {} ({}): {} created", e.getHotelId(), e.getName(), created);
        } finally {
            TenantContext.clear();
        }
    }
}
