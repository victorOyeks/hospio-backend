package com.hospio.hotelconfig.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hospio.common.exception.BadRequestException;
import com.hospio.common.setup.HotelSetup;
import com.hospio.common.setup.SetupSection;
import com.hospio.hotelconfig.entity.Setting;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Hotel setup values: a deposit is 0–100 % of the stay, a till float is never negative; what the web app sends is read into the
 * section's record; stored rows (JSON text per key) are read back typed, a bad row falling back to the default.
 */
class SetupValuesTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private SetupSection.Payments payments(String json) {
        return HotelSetupService.readSent(mapper, SetupSection.Payments.class, json);
    }

    @Test
    void aDepositOverAHundredPercentIsRefused() {
        assertThatThrownBy(() -> HotelSetupService.checkValues(payments("{\"depositPct\":15000}")))
                .isInstanceOf(BadRequestException.class).hasMessage("The deposit is a share of the stay: between 0 and 100 %");
        assertThatThrownBy(() -> HotelSetupService.checkValues(payments("{\"depositPct\":-1}")))
                .isInstanceOf(BadRequestException.class).hasMessage("The deposit is a share of the stay: between 0 and 100 %");
        assertThatThrownBy(() -> HotelSetupService.checkValues(payments("{\"tillFloat\":-1}")))
                .isInstanceOf(BadRequestException.class).hasMessage("The till float can't be negative");
    }

    @Test
    void aDepositOrTillFloatThatIsNotANumberIsRefusedWithItsOwnMessage() {
        assertThatThrownBy(() -> payments("{\"depositPct\":\"half\"}"))
                .isInstanceOf(BadRequestException.class).hasMessage("The deposit is a share of the stay: between 0 and 100 %");
        assertThatThrownBy(() -> payments("{\"tillFloat\":true}"))
                .isInstanceOf(BadRequestException.class).hasMessage("The till float can't be negative");
        assertThatThrownBy(() -> HotelSetupService.readSent(mapper, SetupSection.Staff.class, "{\"lateMinutes\":\"soon\"}"))
                .isInstanceOf(BadRequestException.class).hasMessage("The value of lateMinutes is not valid");
        assertThatThrownBy(() -> HotelSetupService.readSent(mapper, SetupSection.Staff.class, "[1,2]")).isInstanceOf(BadRequestException.class);
    }

    @Test
    void sensibleValuesAndOtherSectionsPass() {
        assertThatCode(() -> HotelSetupService.checkValues(payments("{\"depositPct\":50,\"tillFloat\":15000}"))).doesNotThrowAnyException();
        assertThatCode(() -> HotelSetupService.checkValues(payments("{\"depositPct\":0}"))).doesNotThrowAnyException();
        assertThatCode(() -> HotelSetupService.checkValues(payments("{\"depositPct\":100,\"tillFloat\":null}"))).doesNotThrowAnyException();
        assertThatCode(() -> HotelSetupService.checkValues(
                HotelSetupService.readSent(mapper, SetupSection.Staff.class, "{\"lateMinutes\":15000}"))).doesNotThrowAnyException();
    }

    @Test
    void whatTheWebAppSendsKeepsOnlyTheKeysItSent() {
        SetupSection.Payments p = payments("{\"allowCash\":false,\"depositPct\":20,\"aKeyTheScreenShowedButTheCatalogueLacks\":1}");
        assertThat(p).isEqualTo(new SetupSection.Payments(false, null, null, null, new BigDecimal("20"), null, null, null));
    }

    @Test
    void storedRowsAreReadBackTypedAndABadRowFallsBackToTheDefault() {
        List<Setting> rows = List.of(row("PAYMENTS", "depositPct", "25"), row("PAYMENTS", "receipts", "\"EMAIL\""),
                row("PAYMENTS", "tillFloat", "\"lots\""), row("BASICS", "receiptFooter", "Thank you, not JSON"),
                row("TAXES_CURRENCY", "vatPct", "7.5"), row("LEGACY_SECTION", "x", "1"), row("BRAND", "customKey", "\"kept?\""));

        HotelSetup stored = HotelSetupService.stored(mapper, rows);

        assertThat(stored.payments().depositPct()).isEqualByComparingTo("25");
        assertThat(stored.payments().receipts()).isEqualTo("EMAIL");
        assertThat(stored.payments().tillFloat()).isNull();
        assertThat(stored.basics().receiptFooter()).isEqualTo("Thank you, not JSON");
        assertThat(stored.taxesCurrency().vatPct()).isEqualByComparingTo("7.5");
        assertThat(stored.brand()).isEqualTo(new SetupSection.Brand(null, null, null, null));
        assertThat(stored.staff()).isNull();

        HotelSetup merged = com.hospio.common.setup.SetupCatalogue.withDefaults(stored);
        assertThat(merged.payments().tillFloat()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(merged.payments().allowCash()).isTrue();
        assertThat(merged.staff()).isEqualTo(SetupSection.Staff.DEFAULTS);
    }

    @Test
    void storedJsonTextIsTheSameAsBefore() throws Exception {
        // The rows keep holding each key's JSON text, so rows written by the old code are read as they are and new rows look the same.
        assertThat(mapper.writeValueAsString(SetupSection.TaxesCurrency.DEFAULTS.vatPct())).isEqualTo("7.5");
        assertThat(mapper.writeValueAsString(SetupSection.ApprovalLimits.DEFAULTS.managerExpenseLimit())).isEqualTo("200000");
        assertThat(mapper.writeValueAsString(SetupSection.Basics.DEFAULTS.checkInTime())).isEqualTo("\"14:00\"");
        assertThat(mapper.writeValueAsString(SetupSection.Brand.DEFAULTS.darkSidebar())).isEqualTo("true");
        assertThat(HotelSetupService.fromStored(mapper, SetupSection.Staff.class, Map.of("lateMinutes", "20", "notifyEveryClockIn", "true")))
                .isEqualTo(new SetupSection.Staff(20, true));
    }

    private static Setting row(String section, String key, String value) {
        return Setting.builder().hotelId("h1").section(section).key(key).value(value).build();
    }

    @Test
    void theWholeSetupCarriesTheLiveFeeRateInPayments() {
        HotelSetup all = HotelSetup.DEFAULTS;
        HotelSetup filled = HotelSetupService.withFeeRate(all, new BigDecimal("1.5"));
        assertThat(filled.payments().platformFeePct()).isEqualByComparingTo("1.5");
        assertThat(filled.basics()).isEqualTo(all.basics());
        assertThat(filled.pos()).isEqualTo(all.pos());
        assertThat(filled.payments().guestPaysPlatformFee()).isEqualTo(all.payments().guestPaysPlatformFee());
        assertThat(HotelSetupService.withFeeRate(all, null).payments().platformFeePct()).isNull();
    }
}
