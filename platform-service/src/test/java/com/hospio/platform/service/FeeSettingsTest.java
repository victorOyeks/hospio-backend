package com.hospio.platform.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.hospio.platform.settings.FeeSettings;
import com.hospio.platform.settings.PlatformSettingsCatalogue;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The fees group: defaults from the catalogue, saved values over them, bad values ignored. */
class FeeSettingsTest {

    @Test
    void defaultsMatchTheContract() {
        FeeSettings f = FeeSettings.defaults();
        assertThat(f.model()).isEqualTo("PERCENT_OF_PAYMENTS");
        assertThat(f.perRoom()).isEqualByComparingTo("1500");
        assertThat(f.revenuePct()).isEqualByComparingTo("2");
        assertThat(f.dueDay()).isEqualTo(5);
        assertThat(f.graceDays()).isEqualTo(10);
        assertThat(f.marketerSharePct()).isEqualByComparingTo("20");
        assertThat(f.marketerMonths()).isEqualTo(24);
        assertThat(f.whtPct()).isEqualByComparingTo("5");
        assertThat(f.providerFeePct()).isEqualByComparingTo("0.5");
        assertThat(f.hmsSharePct()).isEqualByComparingTo("1.5");
    }

    @Test
    void savedValuesOverrideAndMalformedOnesFallBack() {
        FeeSettings f = FeeSettings.from(Map.of("dueDay", "7", "graceDays", "soon", "hmsSharePct", 2.25, "model", "PER_ROOM_NIGHT"));
        assertThat(f.dueDay()).isEqualTo(7);
        assertThat(f.graceDays()).isEqualTo(10);
        assertThat(f.hmsSharePct()).isEqualByComparingTo("2.25");
        assertThat(f.model()).isEqualTo("PER_ROOM_NIGHT");
    }

    @Test
    void catalogueHasTheThreeGroups() {
        assertThat(PlatformSettingsCatalogue.defaults().keySet()).containsExactly("general", "fees", "security");
        assertThat(PlatformSettingsCatalogue.defaults("security")).containsEntry("sessionHours", 12).containsEntry("require2Step", false);
        assertThat(PlatformSettingsCatalogue.isGroup("billing")).isFalse();
    }
}
