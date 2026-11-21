package com.hospio.common.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/** Supplier payment terms read as days, and the due date they give. */
class PaymentTermsTest {

    @Test
    void readsTheDaysOutOfTheTerms() {
        assertThat(PaymentTerms.days("14 days")).isEqualTo(14);
        assertThat(PaymentTerms.days("7 days")).isEqualTo(7);
        assertThat(PaymentTerms.days("Net 30")).isEqualTo(30);
        assertThat(PaymentTerms.days("30")).isEqualTo(30);
        assertThat(PaymentTerms.days("2 weeks")).isEqualTo(14);
        assertThat(PaymentTerms.days("1 month")).isEqualTo(30);
    }

    @Test
    void noNumberMeansDueOnDelivery() {
        assertThat(PaymentTerms.days("Pay on delivery")).isZero();
        assertThat(PaymentTerms.days("Cash")).isZero();
        assertThat(PaymentTerms.days("")).isZero();
        assertThat(PaymentTerms.days(null)).isZero();
    }

    @Test
    void dueIsTheReceivedDatePlusTheDays() {
        LocalDate received = LocalDate.of(2026, 11, 21);
        assertThat(PaymentTerms.due(received, 14)).isEqualTo(LocalDate.of(2026, 12, 5));
        assertThat(PaymentTerms.due(received, 0)).isEqualTo(received);
        assertThat(PaymentTerms.due(received, null)).isEqualTo(received);
    }
}
