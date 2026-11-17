package com.hospio.payment.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.hospio.common.enums.PaymentMethod;
import org.junit.jupiter.api.Test;

/** The summary names how each payment came in; a desk transfer is not the test POS. */
class HowPaidTest {

    @Test
    void cashDeskTransfersAndPosProvidersAreNamedApart() {
        assertThat(AdminPaymentQueryService.howPaid(PaymentMethod.CASH, null)).isEqualTo("cash");
        assertThat(AdminPaymentQueryService.howPaid(PaymentMethod.TRANSFER, null)).isEqualTo("desk-transfer");
        assertThat(AdminPaymentQueryService.howPaid(PaymentMethod.TRANSFER, "MONIEPOINT")).isEqualTo("moniepoint");
        assertThat(AdminPaymentQueryService.howPaid(PaymentMethod.CARD, "opay")).isEqualTo("opay");
        assertThat(AdminPaymentQueryService.howPaid(PaymentMethod.CARD, "logging")).isEqualTo("logging");
    }
}
