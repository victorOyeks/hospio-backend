package com.hospio.messaging.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.hospio.messaging.billing.CommBilling;
import org.junit.jupiter.api.Test;

/** What the desk is told about a WhatsApp receipt (docs/whatsapp-receipts.md): the dispatcher's rules, in its order. */
class DeliveryOutlookTest {

    private static final CommBilling.Gate ON = new CommBilling.Gate(true, true, null);
    private static final CommBilling.Gate OFF = new CommBilling.Gate(true, false, "Guest messages are off for this hotel (Hotel setup → Guest messages)");
    private static final String PHONE = "2348035550192";

    @Test
    void guestMessagesOnAndTheNumberAllowedIsQueued() {
        DeliveryOutlook.Outlook o = DeliveryOutlook.decide(ON, false, true, true, PHONE, true);
        assertThat(o.outcome()).isEqualTo("QUEUED");
        assertThat(o.reason()).isNull();
        assertThat(o.guestMessagesOn()).isTrue();
    }

    @Test
    void guestMessagesOffIsSkippedWithTheGatesWords() {
        DeliveryOutlook.Outlook o = DeliveryOutlook.decide(OFF, false, true, true, PHONE, false);
        assertThat(o.outcome()).isEqualTo("SKIPPED");
        assertThat(o.reason()).startsWith("Guest messages are off for this hotel");
        assertThat(o.guestMessagesOn()).isFalse();
    }

    @Test
    void aSuspendedBusinessIsSkipped() {
        DeliveryOutlook.Outlook o = DeliveryOutlook.decide(ON, true, true, true, PHONE, true);
        assertThat(o.outcome()).isEqualTo("SKIPPED");
        assertThat(o.reason()).contains("suspended");
        assertThat(o.guestMessagesOn()).isFalse();
    }

    @Test
    void theStagingAllowListHoldsANumberBack() {
        DeliveryOutlook.Outlook o = DeliveryOutlook.decide(ON, false, true, true, PHONE, false);
        assertThat(o.outcome()).isEqualTo("HELD_BACK");
        assertThat(o.reason()).isEqualTo("Held back: not on this environment's allow-list");
        assertThat(o.guestMessagesOn()).isTrue();
    }

    @Test
    void whatsAppNotConnectedComesBeforeTheAllowList() {
        assertThat(DeliveryOutlook.decide(ON, false, false, true, PHONE, false).outcome()).isEqualTo("NOT_CONNECTED");
    }

    @Test
    void notAPhoneNumberIsNoPhone() {
        DeliveryOutlook.Outlook o = DeliveryOutlook.decide(OFF, false, true, true, "123", true);
        assertThat(o.outcome()).isEqualTo("NO_PHONE");
        assertThat(o.guestMessagesOn()).isFalse();
    }

    @Test
    void withoutANumberOnlyTheHotelsSwitchCounts() {
        assertThat(DeliveryOutlook.decide(ON, false, true, false, null, true).outcome()).isEqualTo("QUEUED");
        assertThat(DeliveryOutlook.decide(OFF, false, true, false, null, true).outcome()).isEqualTo("SKIPPED");
    }

    @Test
    void aFreeKindIsAlwaysOn() {
        assertThat(DeliveryOutlook.decide(CommBilling.Gate.FREE, false, true, false, null, true).guestMessagesOn()).isTrue();
    }
}
