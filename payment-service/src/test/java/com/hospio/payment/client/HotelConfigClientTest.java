package com.hospio.payment.client;

import static org.assertj.core.api.Assertions.assertThat;

import com.hospio.common.setup.SetupSection;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/** Hotel setup → Payments as payments read it: each key the section lacks takes its own default; nothing at all means the defaults. */
class HotelConfigClientTest {

    @Test
    void aPartialSectionFallsBackPerKey() {
        var p = new SetupSection.Payments(false, null, null, null, BigDecimal.TEN, null, null, null);
        assertThat(HotelConfigClient.PaymentSettings.of(p)).isEqualTo(new HotelConfigClient.PaymentSettings(false, true, true, "ASK"));
        var full = new SetupSection.Payments(true, false, true, "EMAIL", null, null, null, null);
        assertThat(HotelConfigClient.PaymentSettings.of(full)).isEqualTo(new HotelConfigClient.PaymentSettings(true, false, true, "EMAIL"));
    }

    @Test
    void nothingReadMeansTheDefaults() {
        assertThat(HotelConfigClient.PaymentSettings.of(null)).isEqualTo(HotelConfigClient.PaymentSettings.defaults());
        assertThat(HotelConfigClient.PaymentSettings.of(new SetupSection.Payments(null, null, null, null, null, null, null, null)))
                .isEqualTo(HotelConfigClient.PaymentSettings.defaults());
    }
}
