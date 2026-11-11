package com.hospio.hotelconfig.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hospio.common.enums.Role;
import com.hospio.common.exception.BadRequestException;
import com.hospio.common.exception.ExternalServiceException;
import com.hospio.common.exception.ForbiddenException;
import com.hospio.common.exception.ResourceNotFoundException;
import com.hospio.common.kafka.EventPublisher;
import com.hospio.common.kafka.KafkaTopics;
import com.hospio.common.kafka.events.SettingChangedEvent;
import com.hospio.common.security.CurrentUser;
import com.hospio.common.security.HospioUserDetails;
import com.hospio.common.setup.SetupCatalogue;
import com.hospio.common.tenancy.Tenant;
import com.hospio.common.tenancy.TenantContext;
import com.hospio.hotelconfig.client.TenantClient;
import com.hospio.hotelconfig.dto.ApplyTemplateRequest;
import com.hospio.hotelconfig.entity.Setting;
import com.hospio.hotelconfig.repository.SettingRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Hotel setup. Nothing is hard-coded in the other services: every rule, list and price the hotel runs
 * on lives here and they read it. Reads lay the hotel's stored values over the {@link SetupCatalogue}
 * defaults, so a brand-new hotel already answers fully. Taxes, currency and approval limits are set by
 * the owner (or by Hospio360 applying a template during onboarding), never by a manager.
 */
@Service
@RequiredArgsConstructor
public class HotelSetupService {

    public static final Set<String> OWNER_ONLY_SECTIONS = SetupCatalogue.OWNER_ONLY_SECTIONS;

    private final SettingRepository settings;
    private final ListsAndPricesService lists;
    private final TenantClient tenantClient;
    private final ObjectMapper objectMapper;
    private final EventPublisher events;

    @Transactional(readOnly = true)
    public Map<String, JsonNode> section(String section) {
        return section(TenantContext.requireHotel(), section);
    }

    @Transactional(readOnly = true)
    public Map<String, JsonNode> section(String hotelId, String section) {
        String sec = section.toUpperCase();
        Map<String, JsonNode> stored = new LinkedHashMap<>();
        settings.findByHotelIdAndSection(hotelId, sec).forEach(s -> stored.put(s.getKey(), parse(s.getValue())));
        return SetupCatalogue.mergeSection(sec, stored);
    }

    @Transactional(readOnly = true)
    public JsonNode value(String hotelId, String section, String key) {
        String sec = section.toUpperCase();
        return settings.findByHotelIdAndSectionAndKey(hotelId, sec, key).map(s -> parse(s.getValue()))
                .orElse(SetupCatalogue.defaults(sec).get(key));
    }

    @Transactional(readOnly = true)
    public Map<String, Map<String, JsonNode>> all(String hotelId) {
        Map<String, Map<String, JsonNode>> stored = new LinkedHashMap<>();
        settings.findByHotelId(hotelId).forEach(s -> stored.computeIfAbsent(s.getSection(), k -> new LinkedHashMap<>()).put(s.getKey(), parse(s.getValue())));
        return SetupCatalogue.merge(stored);
    }

    /** Managers and owners edit; owner-only sections need the owner; the platform may write anything (onboarding). */
    @Transactional
    public Map<String, JsonNode> save(String section, Map<String, JsonNode> values) {
        HospioUserDetails caller = CurrentUser.require();
        String sec = section.toUpperCase();
        if (!caller.isPlatform()) {
            if (OWNER_ONLY_SECTIONS.contains(sec) && !caller.has(Role.OWNER)) {
                throw new ForbiddenException("Only the owner can change " + sec.toLowerCase().replace('_', ' '));
            }
            if (!caller.isManagerOrOwner()) {
                throw new ForbiddenException("Only a manager or the owner can change Hotel setup");
            }
        }
        String hotelId = TenantContext.requireHotel();
        return withHotelTenant(hotelId, () -> apply(hotelId, sec, values, caller.getUserId()));
    }

    /** Hotel setup "Apply a template" and onboarding J8: by template id (from tenant-service) or inline settings. */
    @Transactional
    public Map<String, Map<String, JsonNode>> applyTemplate(ApplyTemplateRequest request) {
        HospioUserDetails caller = CurrentUser.require();
        if (!caller.isPlatform() && !caller.isManagerOrOwner()) {
            throw new ForbiddenException("Only a manager, the owner or Hospio360 can apply a template");
        }
        String hotelId = TenantContext.requireHotel();
        JsonNode template;
        if (request.settings() != null && !request.settings().isNull()) {
            template = request.settings();
        } else if (StringUtils.hasText(request.templateId())) {
            template = tenantClient.template(request.templateId())
                    .orElseThrow(() -> new ResourceNotFoundException("Template not found"));
        } else {
            throw new BadRequestException("Send a templateId or the settings to apply");
        }
        return withHotelTenant(hotelId, () -> {
            applyTemplate(hotelId, template, caller.getUserId());
            return all(hotelId);
        });
    }

    /** Service-to-service: tenant-service's onboarding applies a template's JSON to a hotel. */
    @Transactional
    public void applyTemplate(String hotelId, String templateJson) {
        JsonNode root = parse(templateJson);
        withHotelTenant(hotelId, () -> {
            applyTemplate(hotelId, root, "template");
            return null;
        });
    }

    /** Sections and lists of a template: {"sections": {...}, "lists": {...}} or the flat {SECTION: {...}}. */
    private void applyTemplate(String hotelId, JsonNode root, String by) {
        if (root == null || !root.isObject()) {
            throw new BadRequestException("A template is a JSON object of sections");
        }
        JsonNode sections = root.has("sections") || root.has("lists") ? root.path("sections") : root;
        sections.fields().forEachRemaining(section -> {
            if (!section.getValue().isObject()) {
                return;
            }
            Map<String, JsonNode> values = new LinkedHashMap<>();
            section.getValue().fields().forEachRemaining(e -> values.put(e.getKey(), e.getValue()));
            apply(hotelId, section.getKey().toUpperCase(), values, by);
        });
        root.path("lists").fields().forEachRemaining(list -> {
            List<ListsAndPricesService.SeedItem> items = new ArrayList<>();
            int i = 0;
            for (JsonNode item : list.getValue()) {
                i++;
                if (item.isTextual()) {
                    items.add(new ListsAndPricesService.SeedItem(null, item.asText(), i));
                } else {
                    items.add(new ListsAndPricesService.SeedItem(item.path("code").asText(null), item.path("label").asText(),
                            item.hasNonNull("sortOrder") ? item.get("sortOrder").asInt() : i));
                }
            }
            lists.ensureItems(hotelId, list.getKey().toUpperCase(), items);
        });
    }

    /**
     * A new hotel's defaults (HotelCreated): every catalogue key the hotel lacks and every list that is
     * empty. Idempotent, quiet (no SettingChanged per key) and run inside the hotel's tenant by the caller.
     */
    @Transactional
    public void createDefaults(String hotelId) {
        Set<String> present = new java.util.HashSet<>();
        settings.findByHotelId(hotelId).forEach(s -> present.add(s.getSection() + "/" + s.getKey()));
        SetupCatalogue.SECTIONS.forEach((section, values) -> values.forEach((key, value) -> {
            if (present.add(section + "/" + key)) {
                settings.save(Setting.builder().hotelId(hotelId).section(section).key(key).value(write(value)).updatedBy("defaults").build());
            }
        }));
        SetupCatalogue.LISTS.forEach((listName, items) -> {
            List<ListsAndPricesService.SeedItem> seed = new ArrayList<>();
            for (int i = 0; i < items.size(); i++) {
                seed.add(new ListsAndPricesService.SeedItem(items.get(i).code(), items.get(i).label(), i + 1));
            }
            lists.ensureItems(hotelId, listName, seed);
        });
    }

    /**
     * Platform callers carry no business, and every row here needs one: resolve the hotel's business from
     * tenant-service and run as the system inside it. Business and hotel callers run as themselves.
     */
    <T> T withHotelTenant(String hotelId, Supplier<T> work) {
        Tenant current = TenantContext.current().orElse(null);
        if (current != null && current.businessId() != null) {
            return work.get();
        }
        String businessId = tenantClient.businessOf(hotelId)
                .orElseThrow(() -> new ExternalServiceException("Could not find which business hotel " + hotelId + " belongs to"));
        Tenant bound = Tenant.systemFor(businessId, hotelId, current == null ? null : current.actorId(), current == null ? null : current.actorName());
        return TenantContext.runWith(bound, work);
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
