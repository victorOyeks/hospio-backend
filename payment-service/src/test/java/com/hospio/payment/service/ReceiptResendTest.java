package com.hospio.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hospio.common.delivery.Delivery;
import com.hospio.common.exception.BadRequestException;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Resend receipt goes the way the automatic one does: email when there is one, else WhatsApp's approved receipt template. */
class ReceiptResendTest {

    private static final List<String> VALUES = List.of("Ada", "@hotel", "₦5,000", "Cash", "RCP-1");

    @Test
    void emailIsUsedEvenWhenTheGuestHasAPhone() {
        Delivery d = ReceiptService.resendDelivery(" ada@hospio-qa.test ", "+2348030000000", "RCP-2026-000001", "Receipt RCP-2026-000001.", VALUES);
        assertThat(d.channel()).isEqualTo(Delivery.Channel.EMAIL);
        assertThat(d.to()).isEqualTo("ada@hospio-qa.test");
        assertThat(d.kind()).isEqualTo(Delivery.Kind.RECEIPT);
    }

    @Test
    void aPhoneAloneGetsTheWhatsAppReceiptTemplate() {
        Delivery d = ReceiptService.resendDelivery(null, " +2349035911994 ", "RCP-1", "Receipt RCP-1.", VALUES);
        assertThat(d.channel()).isEqualTo(Delivery.Channel.WHATSAPP);
        assertThat(d.to()).isEqualTo("+2349035911994");
        assertThat(d.template()).isEqualTo("hospio_payment_receipt_v2");
        assertThat(d.params()).isEqualTo(VALUES);
        assertThat(d.kind()).isEqualTo(Delivery.Kind.RECEIPT);
    }

    @Test
    void noContactAtAllIsRefused() {
        assertThatThrownBy(() -> ReceiptService.resendDelivery(" ", null, "RCP-1", "x", VALUES))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("This payment has no guest email or phone · print the receipt or email it from the receipt page");
    }
}
