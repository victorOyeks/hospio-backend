package com.hospio.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hospio.common.delivery.Delivery;
import com.hospio.common.exception.BadRequestException;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Payment receipts go on WhatsApp only (docs/whatsapp-receipts.md): the approved receipt template, never email or print. */
class ReceiptResendTest {

    private static final List<String> VALUES = List.of("Ada", "@hotel", "₦5,000", "Cash", "RCP-1");

    @Test
    void thePhoneGetsTheWhatsAppReceiptTemplate() {
        Delivery d = ReceiptService.whatsAppDelivery(" +2349035911994 ", "RCP-1", "Receipt RCP-1.", VALUES);
        assertThat(d.channel()).isEqualTo(Delivery.Channel.WHATSAPP);
        assertThat(d.to()).isEqualTo("+2349035911994");
        assertThat(d.template()).isEqualTo("hospio_payment_receipt_v2");
        assertThat(d.params()).isEqualTo(VALUES);
        assertThat(d.kind()).isEqualTo(Delivery.Kind.RECEIPT);
        assertThat(d.ref()).isEqualTo("RCP-1");
    }

    @Test
    void noPhoneIsRefusedInWords() {
        assertThatThrownBy(() -> ReceiptService.whatsAppDelivery(" ", "RCP-1", "x", VALUES))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("Receipt not sent: no phone number · type the guest's WhatsApp number to send it");
    }

    @Test
    void whatsAppOrNoChannelIsTaken() {
        assertThatCode(() -> ReceiptService.checkChannel(null)).doesNotThrowAnyException();
        assertThatCode(() -> ReceiptService.checkChannel("whatsapp")).doesNotThrowAnyException();
    }

    @Test
    void printAndEmailFromOlderScreensAreRefusedInWords() {
        for (String c : List.of("PRINT", "EMAIL", "sms")) {
            assertThatThrownBy(() -> ReceiptService.checkChannel(c)).isInstanceOf(BadRequestException.class)
                    .hasMessage("Receipts go to the guest on WhatsApp only: pick WhatsApp or Don't send");
        }
        assertThatThrownBy(() -> ReceiptService.checkChannel("NONE")).isInstanceOf(BadRequestException.class)
                .hasMessageStartingWith("Nothing to send");
    }

    @Test
    void aPaymentKeepsWhatsAppOrNoneWhateverAnOlderScreenSent() {
        assertThat(PaymentService.receiptChoice("PRINT")).isEqualTo("NONE");
        assertThat(PaymentService.receiptChoice("never")).isEqualTo("NONE");
        assertThat(PaymentService.receiptChoice("NONE")).isEqualTo("NONE");
        assertThat(PaymentService.receiptChoice("EMAIL")).isEqualTo("WHATSAPP");
        assertThat(PaymentService.receiptChoice("whatsapp")).isEqualTo("WHATSAPP");
        assertThat(PaymentService.receiptChoice("ASK")).isEqualTo("ASK");
        assertThat(PaymentService.receiptChoice(null)).isNull();
    }
}
