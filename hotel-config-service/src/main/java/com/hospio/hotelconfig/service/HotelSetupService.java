package com.hospio.hotelconfig.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hospio.common.exception.BadRequestException;
import com.hospio.common.exception.ForbiddenException;
import com.hospio.common.kafka.EventPublisher;
import com.hospio.common.kafka.KafkaTopics;
import com.hospio.common.kafka.events.SettingChangedEvent;
import com.hospio.common.security.CurrentUser;
import com.hospio.common.security.HospioUserDetails;
import com.hospio.common.tenancy.TenantContext;
import com.hospio.hotelconfig.entity.Setting;
import com.hospio.hotelconfig.repository.SettingRepository;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Hotel setup. Nothing is hard-coded: every rule, list and price the hotel runs on lives here and
 * every other service reads it. Taxes, currency and approval limits are set by the owner only, and
 * H.M.S never changes them.
 */
@Service
@RequiredArgsConstructor
public class HotelSetupService {

    public static final Set<String> OWNER_ONLY_SECTIONS = Set.of("TAXES_CURRENCY", "APPROVAL_LIMITS");

    private final SettingRepository settings;
    private final ObjectMapper objectMapper;
    private final EventPublisher events;

    @Transactional(readOnly = true)
    public Map<String, JsonNode> section(String section) {
        return section(TenantContext.requireHotel(), section);
    }

    @Transactional(readOnly = true)
    public Map<String, JsonNode> section(String hotelId, String section) {
        Map<String, JsonNode> out = new LinkedHashMap<>();
        settings.findByHotelIdAndSection(hotelId, section.toUpperCase()).forEach(s -> out.put(s.getKey(), parse(s.getValue())));
        return out;
    }

    @Transactional(readOnly = true)
    public JsonNode value(String hotelId, String section, String key) {
        return settings.findByHotelIdAndSectionAndKey(hotelId, section.toUpperCase(), key).map(s -> parse(s.getValue())).orElse(null);
    }

    @Transactional(readOnly = true)
    public Map<String, Map<String, JsonNode>> all(String hotelId) {
        Map<String, Map<String, JsonNode>> out = new LinkedHashMap<>();
        settings.findByHotelId(hotelId).forEach(s -> out.computeIfAbsent(s.getSection(), k -> new LinkedHashMap<>()).put(s.getKey(), parse(s.getValue())));
        return out;
    }

    @Transactional
    public Map<String, JsonNode> save(String section, Map<String, JsonNode> values) {
        HospioUserDetails caller = CurrentUser.require();
        String sec = section.toUpperCase();
        if (OWNER_ONLY_SECTIONS.contains(sec)) {
            if (caller.isPlatform()) {
                throw new ForbiddenException("H.M.S never changes taxes, currency or approval limits");
            }
            if (!caller.has(com.hospio.common.enums.Role.OWNER)) {
                throw new ForbiddenException("Only the owner can change " + sec.toLowerCase().replace('_', ' '));
            }
        } else if (!caller.isPlatform() && !caller.isManagerOrOwner()) {
            throw new ForbiddenException("Only a manager or the owner can change Hotel setup");
        }
        String hotelId = TenantContext.requireHotel();
        return apply(hotelId, sec, values, caller.getUserId());
    }

    /** Onboarding applies a whole setup template: {"SECTION": {"key": value, ...}, ...}. */
    @Transactional
    public void applyTemplate(String hotelId, String templateJson) {
        JsonNode root = parse(templateJson);
        if (root == null || !root.isObject()) {
            throw new BadRequestException("A template is a JSON object of sections");
        }
        root.fields().forEachRemaining(section -> {
            Map<String, JsonNode> values = new LinkedHashMap<>();
            section.getValue().fields().forEachRemaining(e -> values.put(e.getKey(), e.getValue()));
            apply(hotelId, section.getKey().toUpperCase(), values, "template");
        });
    }

    private Map<String, JsonNode> apply(String hotelId, String section, Map<String, JsonNode> values, String by) {
        for (Map.Entry<String, JsonNode> e : values.entrySet()) {
            Setting setting = settings.findByHotelIdAndSectionAndKey(hotelId, section, e.getKey())
                    .orElseGet(() -> Setting.builder().hotelId(hotelId).section(section).key(e.getKey()).build());
            String old = setting.getValue();
            String fresh = write(e.getValue());
            if (old != null && old.equals(fresh)) {
                continue;
            }
            setting.setValue(fresh);
            setting.setUpdatedBy(by);
            settings.save(setting);
            events.publish(KafkaTopics.SETTING_CHANGED, SettingChangedEvent.builder()
                    .hotelId(hotelId).section(section).key(e.getKey()).oldValue(old).newValue(fresh).build());
        }
        return section(hotelId, section);
    }

    private JsonNode parse(String json) {
        try {
            return json == null ? null : objectMapper.readTree(json);
        } catch (JsonProcessingException e) {
            return objectMapper.getNodeFactory().textNode(json);
        }
    }

    private String write(JsonNode node) {
        try {
            return objectMapper.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            throw new BadRequestException("Value could not be stored");
        }
    }
}
