package com.hospio.platform.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hospio.common.exception.BadRequestException;
import com.hospio.common.security.CurrentUser;
import com.hospio.common.tenancy.Tenant;
import com.hospio.common.tenancy.TenantContext;
import com.hospio.platform.entity.PlatformSetting;
import com.hospio.platform.repository.PlatformSettingRepository;
import com.hospio.platform.settings.FeeSettings;
import com.hospio.platform.settings.GeneralSettings;
import com.hospio.platform.settings.PlatformSettings;
import com.hospio.platform.settings.PlatformSettingsCatalogue;
import com.hospio.platform.settings.SecuritySettings;
import java.lang.reflect.Constructor;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Platform settings: stored keys laid over the defaults, saved a group at a time. Each group is a record; each of its fields is one
 * stored row (key = field name, value = the field as JSON). A stored value that can't be read as its field's type reads as the default.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PlatformSettingsService {

    private final PlatformSettingRepository settings;
    private final ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public PlatformSettings all() {
        return new PlatformSettings(general(), feesGroup(), security());
    }

    @Transactional(readOnly = true)
    public GeneralSettings general() {
        return read(PlatformSettingsCatalogue.GENERAL, GeneralSettings.DEFAULTS);
    }

    /** The fees group as the caller sees it (the admin screen); {@link #fees()} works from any thread. */
    @Transactional(readOnly = true)
    public FeeSettings feesGroup() {
        return read(PlatformSettingsCatalogue.FEES, FeeSettings.DEFAULTS);
    }

    @Transactional(readOnly = true)
    public SecuritySettings security() {
        return read(PlatformSettingsCatalogue.SECURITY, SecuritySettings.DEFAULTS);
    }

    /** The fees group; works from any thread, as the platform (jobs, internal calls). */
    @Transactional(readOnly = true)
    public FeeSettings fees() {
        return TenantContext.runWith(Tenant.system("settings"), this::feesGroup);
    }

    @Transactional
    public GeneralSettings update(GeneralSettings values) {
        save(PlatformSettingsCatalogue.GENERAL, values);
        return general();
    }

    @Transactional
    public FeeSettings update(FeeSettings values) {
        save(PlatformSettingsCatalogue.FEES, values);
        return feesGroup();
    }

    @Transactional
    public SecuritySettings update(SecuritySettings values) {
        save(PlatformSettingsCatalogue.SECURITY, values);
        return security();
    }

    /** Writes every default that is not stored yet (the seeder). Never overwrites a saved value. */
    @Transactional
    public int seedDefaults() {
        int created = 0;
        for (Map.Entry<String, Record> g : PlatformSettingsCatalogue.defaults().entrySet()) {
            for (RecordComponent c : g.getValue().getClass().getRecordComponents()) {
                if (settings.findByGroupNameAndSettingKey(g.getKey(), c.getName()).isEmpty()) {
                    settings.save(PlatformSetting.builder().groupName(g.getKey()).settingKey(c.getName())
                            .value(write(valueOf(c, g.getValue()))).updatedBy("seed").build());
                    created++;
                }
            }
        }
        return created;
    }

    /** The group's defaults with each stored, readable value laid over its field. */
    <T extends Record> T read(String group, T defaults) {
        Map<String, String> stored = new HashMap<>();
        for (PlatformSetting s : settings.findByGroupName(group)) {
            stored.put(s.getSettingKey(), s.getValue());
        }
        RecordComponent[] components = defaults.getClass().getRecordComponents();
        Object[] values = new Object[components.length];
        for (int i = 0; i < components.length; i++) {
            RecordComponent c = components[i];
            Object fallback = valueOf(c, defaults);
            String json = stored.get(c.getName());
            values[i] = json == null ? fallback : parse(json, c, fallback);
        }
        return construct(defaults, components, values);
    }

    /** Stores every field that was sent (not null); the others keep what they had. */
    private void save(String group, Record values) {
        if (values == null) {
            throw new BadRequestException("Send the group's values");
        }
        String by = CurrentUser.get().map(u -> u.getUserId()).orElse("system");
        for (RecordComponent c : values.getClass().getRecordComponents()) {
            Object value = valueOf(c, values);
            if (value == null) {
                continue;
            }
            PlatformSetting s = settings.findByGroupNameAndSettingKey(group, c.getName())
                    .orElseGet(() -> PlatformSetting.builder().groupName(group).settingKey(c.getName()).build());
            s.setValue(write(value));
            s.setUpdatedBy(by);
            settings.save(s);
        }
    }

    private Object parse(String json, RecordComponent c, Object fallback) {
        try {
            Object v = objectMapper.readValue(json, objectMapper.constructType(c.getGenericType()));
            return v == null ? fallback : v;
        } catch (Exception e) {
            log.warn("Platform setting {} can't be read as {} ({}); using its default", c.getName(), c.getType().getSimpleName(), e.getMessage());
            return fallback;
        }
    }

    private static Object valueOf(RecordComponent c, Record r) {
        try {
            return c.getAccessor().invoke(r);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Can't read setting " + c.getName(), e);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T extends Record> T construct(T like, RecordComponent[] components, Object[] values) {
        try {
            Class<?>[] types = Arrays.stream(components).map(RecordComponent::getType).toArray(Class<?>[]::new);
            Constructor<?> canonical = like.getClass().getDeclaredConstructor(types);
            return (T) canonical.newInstance(values);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Can't build settings " + like.getClass().getSimpleName(), e);
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
