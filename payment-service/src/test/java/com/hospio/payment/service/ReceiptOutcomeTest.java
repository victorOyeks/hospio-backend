package com.hospio.payment.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.hospio.payment.dto.ReceiptOutlook;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The desk is told what became of a WhatsApp receipt, never "sent" when it wasn't (docs/whatsapp-receipts.md). */
class ReceiptOutcomeTest {

    private static final String TO = "+2348035550192";

    private static String say(String outcome, String reason) {
        return new ReceiptOutlook(outcome, reason, null).message("RCP-1", TO);
    }

    @Test
    void queuedSaysSent() {
        assertThat(say("QUEUED", null)).isEqualTo("Receipt RCP-1 sent on WhatsApp to +2348035550192");
        assertThat(new ReceiptOutlook("QUEUED", null, true).sent()).isTrue();
    }

    @Test
    void guestMessagesOffSaysNotSentWithTheReason() {
        assertThat(say("SKIPPED", "Guest messages are off for this hotel (Hotel setup → Guest messages)"))
                .isEqualTo("Receipt not sent: guest messages are off for this hotel (Hotel setup → Guest messages)");
        assertThat(new ReceiptOutlook("SKIPPED", "x", false).sent()).isFalse();
    }

    @Test
    void aSuspendedBusinessLosesTheNotSentPrefix() {
        assertThat(say("SKIPPED", "Not sent: the business's Hospio360 account is suspended"))
                .isEqualTo("Receipt not sent: the business's Hospio360 account is suspended");
    }

    @Test
    void theAllowListSaysHeldBackOnTheTestServer() {
        assertThat(say("HELD_BACK", "Held back: not on this environment's allow-list"))
                .isEqualTo("Receipt held back (test server): +2348035550192 is not on this server's allow-list");
    }

    @Test
    void noPhoneAndNotConnectedSayNotSent() {
        assertThat(say("NO_PHONE", null)).isEqualTo("Receipt not sent: no phone number");
        assertThat(say("NOT_CONNECTED", null)).isEqualTo("Receipt not sent: WhatsApp is not connected on this server");
    }

    @Test
    void unknownStillSaysSentAsTheReceiptWasHandedOver() {
        assertThat(say("UNKNOWN", null)).isEqualTo("Receipt RCP-1 sent on WhatsApp to +2348035550192");
        assertThat(ReceiptOutlook.unknown().sent()).isTrue();
    }

    @Test
    void theAnswerCarriesTheOutcomeAndTheMessage() {
        ReceiptService.Resent r = ReceiptService.resent("RCP-1", TO, "WHATSAPP", List.of(),
                new ReceiptOutlook("SKIPPED", "Guest messages are off for this hotel (Hotel setup → Guest messages)", false));
        assertThat(r.outcome()).isEqualTo("SKIPPED");
        assertThat(r.guestMessagesOn()).isFalse();
        assertThat(r.message()).startsWith("Receipt not sent: guest messages are off");
    }
}
