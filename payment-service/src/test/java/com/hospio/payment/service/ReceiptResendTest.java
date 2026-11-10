package com.hospio.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hospio.common.delivery.Delivery;
import com.hospio.common.exception.BadRequestException;
import org.junit.jupiter.api.Test;

/** Resend receipt goes by email, the channel that is sent for real; WhatsApp is coming soon, so a phone alone is refused in words. */
class ReceiptResendTest {

    @Test
    void emailIsUsedEvenWhenTheGuestHasAPhone() {
        Delivery d = ReceiptService.resendDelivery(" ada@hospio-qa.test ", "+2348030000000", "RCP-2026-000001", "Receipt RCP-2026-000001.");
        assertThat(d.channel()).isEqualTo(Delivery.Channel.EMAIL);
        assertThat(d.to()).isEqualTo("ada@hospio-qa.test");
        assertThat(d.kind()).isEqualTo(Delivery.Kind.RECEIPT);
    }

    @Test
    void aPhoneAloneIsRefusedBecauseWhatsAppIsComingSoon() {
        assertThatThrownBy(() -> ReceiptService.resendDelivery(null, "+2348030000000", "RCP-1", "x"))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("This guest has no email · print the receipt or email it from the receipt page (WhatsApp receipts are coming soon)");
    }

    @Test
    void noContactAtAllIsRefused() {
        assertThatThrownBy(() -> ReceiptService.resendDelivery(" ", null, "RCP-1", "x"))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("This payment has no guest email · print the receipt or email it from the receipt page");
    }
}
