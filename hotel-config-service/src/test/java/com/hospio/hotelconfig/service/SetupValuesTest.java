package com.hospio.hotelconfig.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.hospio.common.exception.BadRequestException;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Hotel setup → Money & payments: a deposit is 0–100 % of the stay, a till float is never negative. */
class SetupValuesTest {

    private static JsonNode n(double v) {
        return JsonNodeFactory.instance.numberNode(v);
    }

    @Test
    void aDepositOverAHundredPercentIsRefused() {
        assertThatThrownBy(() -> HotelSetupService.checkValues("PAYMENTS", Map.of("depositPct", n(15000))))
                .isInstanceOf(BadRequestException.class).hasMessage("The deposit is a share of the stay: between 0 and 100 %");
        assertThatThrownBy(() -> HotelSetupService.checkValues("PAYMENTS", Map.of("tillFloat", n(-1))))
                .isInstanceOf(BadRequestException.class).hasMessage("The till float can't be negative");
    }

    @Test
    void sensibleValuesAndOtherSectionsPass() {
        assertThatCode(() -> HotelSetupService.checkValues("PAYMENTS", Map.of("depositPct", n(50), "tillFloat", n(15000)))).doesNotThrowAnyException();
        assertThatCode(() -> HotelSetupService.checkValues("PAYMENTS", Map.of("depositPct", n(0)))).doesNotThrowAnyException();
        assertThatCode(() -> HotelSetupService.checkValues("STAFF", Map.of("lateMinutes", n(15000)))).doesNotThrowAnyException();
    }
}
