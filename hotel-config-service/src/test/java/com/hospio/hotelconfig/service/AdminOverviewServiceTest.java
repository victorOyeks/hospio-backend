package com.hospio.hotelconfig.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.hospio.common.setup.HotelSetup;
import com.hospio.common.setup.SetupCatalogue;
import com.hospio.common.setup.SetupSection;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/** Setup completeness counts only catalogue keys whose value differs from the default. */
class AdminOverviewServiceTest {

    @Test
    void defaultsScoreZeroAndEveryChangedKeyScoresFull() {
        assertThat(AdminOverviewService.setupCompletePct(HotelSetup.DEFAULTS)).isZero();

        HotelSetup all = HotelSetup.of(name -> SetupCatalogue.build(SetupCatalogue.SECTIONS.get(name), (key, type) ->
                type == String.class ? "changed-" + key : type == Boolean.class ? !(Boolean) valueOf(name, key)
                        : type == Integer.class ? 9999 : new BigDecimal("12345.6")));
        assertThat(AdminOverviewService.setupCompletePct(all)).isEqualTo(100);
    }

    @Test
    void oneChangedKeyCountsProportionallyAndTheSameRateWrittenDifferentlyIsNotAChange() {
        HotelSetup stored = HotelSetup.of(name -> switch (name) {
            case SetupCatalogue.MESSAGING -> new SetupSection.Messaging("+2348010000000", null, null, null);
            case SetupCatalogue.TAXES_CURRENCY -> new SetupSection.TaxesCurrency(null, new BigDecimal("7.50"), null, null, null);
            default -> null;
        });
        assertThat(AdminOverviewService.setupCompletePct(stored)).isEqualTo((int) Math.round(100.0 / SetupCatalogue.keyCount()));
        assertThat(AdminOverviewService.setupCompletePct(HotelSetup.of(name -> null))).isZero();
        assertThat(AdminOverviewService.setupCompletePct(null)).isZero();
    }

    private static Object valueOf(String section, String key) {
        return SetupCatalogue.values(SetupCatalogue.defaults(section)).stream().filter(v -> v.key().equals(key)).findFirst().orElseThrow().value();
    }
}
