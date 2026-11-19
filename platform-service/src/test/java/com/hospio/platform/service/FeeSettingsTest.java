package com.hospio.platform.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hospio.platform.entity.PlatformSetting;
import com.hospio.platform.repository.PlatformSettingRepository;
import com.hospio.platform.settings.FeeSettings;
import com.hospio.platform.settings.GeneralSettings;
import com.hospio.platform.settings.PlatformSettingsCatalogue;
import com.hospio.platform.settings.SecuritySettings;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** The settings groups: defaults, saved values laid over them key by key, unreadable values ignored, the JSON keys the web app reads. */
class FeeSettingsTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final PlatformSettingRepository repo = mock(PlatformSettingRepository.class);
    private final PlatformSettingsService service = new PlatformSettingsService(repo, mapper);

    private static PlatformSetting row(String group, String key, String json) {
        return PlatformSetting.builder().groupName(group).settingKey(key).value(json).build();
    }

    @Test
    void defaultsMatchTheContract() {
        FeeSettings f = FeeSettings.defaults();
        assertThat(f.model()).isEqualTo("PERCENT_OF_SALES");
        assertThat(f.perRoom()).isEqualByComparingTo("1500");
        assertThat(f.revenuePct()).isEqualByComparingTo("1.5");
        assertThat(f.trialDays()).isEqualTo(30);
        assertThat(f.dueDay()).isEqualTo(5);
        assertThat(f.graceDays()).isEqualTo(10);
        assertThat(f.marketerSharePct()).isEqualByComparingTo("20");
        assertThat(f.marketerMonths()).isEqualTo(12);
        assertThat(f.whtPct()).isEqualByComparingTo("5");
        assertThat(f.providerFeePct()).isEqualByComparingTo("0.5");
        assertThat(f.hmsSharePct()).isEqualByComparingTo("1.5");
    }

    @Test
    void savedValuesOverrideAndMalformedOnesFallBack() {
        when(repo.findByGroupName("fees")).thenReturn(List.of(row("fees", "dueDay", "\"7\""), row("fees", "graceDays", "\"soon\""),
                row("fees", "hmsSharePct", "2.25"), row("fees", "model", "\"PER_ROOM_NIGHT\""), row("fees", "trialDays", "null"),
                row("fees", "whtPct", "not json"), row("fees", "retired", "1")));
        FeeSettings f = service.feesGroup();
        assertThat(f.dueDay()).isEqualTo(7);
        assertThat(f.graceDays()).isEqualTo(10);
        assertThat(f.hmsSharePct()).isEqualByComparingTo("2.25");
        assertThat(f.model()).isEqualTo("PER_ROOM_NIGHT");
        assertThat(f.trialDays()).isEqualTo(30);
        assertThat(f.whtPct()).isEqualByComparingTo("5");
    }

    @Test
    void storedRowsOfEveryGroupRead() {
        when(repo.findByGroupName("general")).thenReturn(List.of(row("general", "company", "\"Hospio360 Technologies Ltd\"")));
        when(repo.findByGroupName("security")).thenReturn(List.of(row("security", "auditRetentionDays", "200"),
                row("security", "require2Step", "true")));
        assertThat(service.general().company()).isEqualTo("Hospio360 Technologies Ltd");
        assertThat(service.general().timezone()).isEqualTo("Africa/Lagos");
        SecuritySettings s = service.security();
        assertThat(s.auditRetentionDays()).isEqualTo(200);
        assertThat(s.require2Step()).isTrue();
        assertThat(s.sessionHours()).isEqualTo(12);
    }

    @Test
    @SuppressWarnings("unchecked")
    void everyGroupKeepsTheKeysTheWebAppReads() throws Exception {
        when(repo.findByGroupName(anyString())).thenReturn(List.of());
        Map<String, Object> json = mapper.readValue(mapper.writeValueAsString(service.all()), LinkedHashMap.class);
        assertThat(json).containsOnlyKeys("general", "fees", "security", "system");
        assertThat((Map<String, Object>) json.get("general")).containsOnlyKeys("company", "supportEmail", "supportWhatsApp", "website", "timezone");
        assertThat(((Map<String, Object>) json.get("fees")).keySet()).containsExactly("model", "perRoom", "revenuePct", "trialDays", "dueDay",
                "graceDays", "marketerSharePct", "marketerMonths", "whtPct", "providerFeePct", "hmsSharePct", "marketerMinPayout", "invoiceVatPct",
                "invoiceMinimum");
        assertThat((Map<String, Object>) json.get("fees")).containsEntry("perRoom", 1500).containsEntry("revenuePct", 1.5).containsEntry("trialDays", 30);
        assertThat((Map<String, Object>) json.get("security")).containsEntry("sessionHours", 12).containsEntry("require2Step", false)
                .containsEntry("adminIpAllowlist", false).containsEntry("auditRetentionDays", 183);
    }

    @Test
    void aSaveStoresOnlyWhatWasSentAsJson() {
        List<PlatformSetting> saved = new ArrayList<>();
        when(repo.findByGroupNameAndSettingKey(anyString(), anyString())).thenReturn(Optional.empty());
        when(repo.save(any())).thenAnswer(inv -> {
            saved.add(inv.getArgument(0));
            return inv.getArgument(0);
        });
        FeeSettings body = new FeeSettings(null, new BigDecimal("2000"), null, null, 6, null, null, null, null, null, null, null, null, null);
        service.update(body);
        assertThat(saved).extracting(PlatformSetting::getSettingKey).containsExactly("perRoom", "dueDay");
        assertThat(saved).extracting(PlatformSetting::getValue).containsExactly("2000", "6");
        verify(repo, never()).findByGroupNameAndSettingKey("fees", "model");
    }

    @Test
    void seedingWritesEveryDefaultOnce() {
        when(repo.findByGroupNameAndSettingKey(anyString(), anyString())).thenReturn(Optional.empty());
        // System health's alertsOn has no default to store (the environment decides until someone saves it).
        assertThat(service.seedDefaults()).isEqualTo(5 + 14 + 4 + 12);
    }

    @Test
    void catalogueHasTheFourGroups() {
        assertThat(PlatformSettingsCatalogue.defaults().keySet()).containsExactly("general", "fees", "security", "system");
        assertThat(SecuritySettings.DEFAULTS.sessionHours()).isEqualTo(12);
        assertThat(SecuritySettings.DEFAULTS.require2Step()).isFalse();
        assertThat(GeneralSettings.DEFAULTS.company()).isEqualTo("Hospio360");
        assertThat(PlatformSettingsCatalogue.isGroup("billing")).isFalse();
    }
}
