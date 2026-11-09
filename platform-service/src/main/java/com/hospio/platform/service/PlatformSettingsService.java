package com.hospio.platform.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hospio.common.exception.BadRequestException;
import com.hospio.common.security.CurrentUser;
import com.hospio.common.tenancy.Tenant;
import com.hospio.common.tenancy.TenantContext;
import com.hospio.platform.entity.PlatformSetting;
import com.hospio.platform.repository.PlatformSettingRepository;
import com.hospio.platform.settings.FeeSettings;
import com.hospio.platform.settings.PlatformSettingsCatalogue;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Platform settings: stored keys laid over the catalogue defaults, saved a group at a time. */
@Slf4j
@Service
@RequiredArgsConstructor
public class PlatformSettingsService {

    private final PlatformSettingRepository settings;
    private final ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public Map<String, Map<String, Object>> all() {
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        for (String group : PlatformSettingsCatalogue.GROUPS) {
            out.put(group, group(group));
        }
        return out;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> group(String group) {
        if (!PlatformSettingsCatalogue.isGroup(group)) {
            throw new BadRequestException("Unknown settings group: " + group);
        }
        Map<String, Object> merged = new LinkedHashMap<>(PlatformSettingsCatalogue.defaults(group));
        for (PlatformSetting s : settings.findByGroupName(group)) {
            merged.put(s.getSettingKey(), read(s.getValue()));
        }
        return merged;
    }

    /** The fees group as typed values; works from any thread, as the platform (jobs, internal calls). */
    @Transactional(readOnly = true)
    public FeeSettings fees() {
        return TenantContext.runWith(Tenant.system("settings"), () -> FeeSettings.from(group(PlatformSettingsCatalogue.FEES)));
    }

    @Transactional
    public Map<String, Object> update(String group, Map<String, Object> values) {
        if (!PlatformSettingsCatalogue.isGroup(group)) {
            throw new BadRequestException("Unknown settings group: " + group);
        }
        if (values == null) {
            throw new BadRequestException("Send the group's values");
        }
        String by = CurrentUser.get().map(u -> u.getUserId()).orElse("system");
        for (Map.Entry<String, Object> e : values.entrySet()) {
            if (e.getKey() == null || e.getKey().isBlank() || e.getKey().length() > 60) {
                throw new BadRequestException("Bad setting key");
            }
            PlatformSetting s = settings.findByGroupNameAndSettingKey(group, e.getKey())
                    .orElseGet(() -> PlatformSetting.builder().groupName(group).settingKey(e.getKey()).build());
            s.setValue(write(e.getValue()));
            s.setUpdatedBy(by);
            settings.save(s);
        }
        return group(group);
    }

    /** Writes every catalogue default that is not stored yet (the seeder). Never overwrites a saved value. */
    @Transactional
    public int seedDefaults() {
        int created = 0;
        for (Map.Entry<String, Map<String, Object>> g : PlatformSettingsCatalogue.defaults().entrySet()) {
            for (Map.Entry<String, Object> e : g.getValue().entrySet()) {
                if (settings.findByGroupNameAndSettingKey(g.getKey(), e.getKey()).isEmpty()) {
                    settings.save(PlatformSetting.builder().groupName(g.getKey()).settingKey(e.getKey()).value(write(e.getValue())).updatedBy("seed").build());
                    created++;
                }
            }
        }
        return created;
    }

    private Object read(String json) {
        try {
            JsonNode node = objectMapper.readTree(json);
            return objectMapper.convertValue(node, Object.class);
        } catch (Exception e) {
            return json;
        }
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new BadRequestException("That value cannot be stored");
        }
    }
}
