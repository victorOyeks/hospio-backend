package com.hospio.platform.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hospio.common.kafka.events.HospioEvent;
import com.hospio.common.security.CurrentUser;
import com.hospio.common.security.HospioUserDetails;
import com.hospio.common.tenancy.TenantContext;
import com.hospio.platform.entity.ActivityEntry;
import com.hospio.platform.repository.ActivityEntryRepository;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Every action → activity log. Manager · Activity log, Owner · Operations 4-5, Admin · H3 and Changes by H.M.S. */
@Service
@RequiredArgsConstructor
public class ActivityLogService {

    private final ActivityEntryRepository entries;
    private final ObjectMapper objectMapper;

    @Transactional
    public void record(String topic, HospioEvent event) {
        if (event.getEventId() != null && entries.findByEventId(event.getEventId()).isPresent()) {
            return;
        }
        String payload;
        try {
            payload = objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            payload = null;
        }
        String summary = event.summary();
        entries.save(ActivityEntry.builder().businessId(event.getBusinessId()).hotelId(event.getHotelId()).eventId(event.getEventId())
                .eventType(event.type()).topic(topic).actorId(event.getActorId()).actorName(event.getActorName())
                .summary(summary == null ? event.type() : (summary.length() > 500 ? summary.substring(0, 500) : summary))
                .payload(payload).occurredAt(event.getOccurredAt() == null ? Instant.now() : event.getOccurredAt()).build());
    }

    /** A manager sees their hotel; the owner the whole business; H.M.S a business they name. */
    @Transactional(readOnly = true)
    public Page<ActivityEntry> list(String businessId, int page, int size, boolean platformOnly) {
        HospioUserDetails user = CurrentUser.require();
        PageRequest pr = PageRequest.of(page, Math.min(size, 200));
        String business = user.isPlatform() ? businessId : TenantContext.requireBusiness();
        if (platformOnly) {
            return entries.findByBusinessIdAndActorNameStartingWithOrderByOccurredAtDesc(business, "H.M.S", pr);
        }
        String hotel = TenantContext.current().map(t -> t.hotelId()).orElse(null);
        return hotel != null && !user.isPlatform() ? entries.findByHotelIdOrderByOccurredAtDesc(hotel, pr)
                : entries.findByBusinessIdOrderByOccurredAtDesc(business, pr);
    }

    @Transactional(readOnly = true)
    public ActivityEntry get(String id) {
        return entries.findById(id).orElseThrow(() -> new com.hospio.common.exception.ResourceNotFoundException("Entry not found"));
    }
}
