package com.hospio.hotelconfig.kafka;

import com.hospio.common.kafka.KafkaTopics;
import com.hospio.common.kafka.events.HotelCreatedEvent;
import com.hospio.common.tenancy.TenantBinder;
import com.hospio.common.tenancy.TenantContext;
import com.hospio.hotelconfig.service.HotelSetupService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** A new hotel gets the catalogue defaults: every section's keys and every list's entries, once. */
@Slf4j
@Component
@RequiredArgsConstructor
public class HotelConfigEventListener {

    private final HotelSetupService setup;
    private final TenantBinder tenant;

    @Transactional
    @KafkaListener(topics = KafkaTopics.HOTEL_CREATED)
    public void onHotelCreated(HotelCreatedEvent e) {
        if (e.getHotelId() == null || e.getBusinessId() == null) {
            log.warn("HotelCreated without hotel or business ({} / {}); no defaults created", e.getHotelId(), e.getBusinessId());
            return;
        }
        tenant.bind(e);
        try {
            setup.createDefaults(e.getHotelId());
            log.info("Default Hotel setup created for hotel {} ({})", e.getHotelId(), e.getName());
        } finally {
            TenantContext.clear();
        }
    }
}
