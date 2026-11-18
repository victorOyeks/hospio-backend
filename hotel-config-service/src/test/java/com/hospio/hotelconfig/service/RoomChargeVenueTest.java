package com.hospio.hotelconfig.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.hospio.common.setup.SetupSection;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/** A location without accommodation reads charge-to-room as off, keeping its tab rules (docs/plan-venue-type.md). */
class RoomChargeVenueTest {

    @Test
    void roomChargeGoesOffAndTheTabRulesStay() {
        SetupSection.Outlets on = new SetupSection.Outlets(true, true, BigDecimal.valueOf(80000));
        SetupSection.Outlets off = HotelSetupService.withoutRoomCharge(on);
        assertThat(off.roomCharge()).isFalse();
        assertThat(off.tabs()).isTrue();
        assertThat(off.tabLimit()).isEqualByComparingTo("80000");
    }
}
