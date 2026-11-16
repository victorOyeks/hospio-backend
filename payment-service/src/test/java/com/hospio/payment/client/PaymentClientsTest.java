package com.hospio.payment.client;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/** payment-service's reads of other services, typed: the fields used, 404 as empty, defaults when the other service is down. */
class PaymentClientsTest {

    private static final String KEY = "internal-key";

    @Test
    void feeSettingsReadNumbersOrTextAndFallBackPerSetting() {
        try (StubServer s = new StubServer()) {
            PlatformClient client = new PlatformClient(s.url(), StubServer.jwt(), KEY);
            s.reply("/internal/settings/fees", 200, "{\"status\":true,\"data\":{\"providerFeePct\":0.75,\"hmsSharePct\":\"2\",\"whtPct\":5}}");
            assertThat(client.feeSettings()).isEqualTo(new PlatformClient.FeeSettings(new BigDecimal("0.75"), new BigDecimal("2")));
            s.reply("/internal/settings/fees", 200, "{\"status\":true,\"data\":{\"providerFeePct\":\"abc\"}}");
            assertThat(client.feeSettings()).isEqualTo(PlatformClient.FeeSettings.defaults());
            s.reply("/internal/settings/fees", 200, "{\"status\":true,\"data\":null}");
            assertThat(client.feeSettings()).isEqualTo(PlatformClient.FeeSettings.defaults());
        }
        assertThat(new PlatformClient(StubServer.downUrl(), StubServer.jwt(), KEY).feeSettings()).isEqualTo(PlatformClient.FeeSettings.defaults());
    }

    @Test
    void bookingIsEmptyOn404AndUnknownWhenReservationIsDown() {
        try (StubServer s = new StubServer()) {
            ReservationClient client = new ReservationClient(s.url(), StubServer.jwt(), KEY);
            s.reply("/internal/bookings/b1", 200, "{\"status\":true,\"data\":{\"id\":\"b1\",\"status\":\"CANCELLED\",\"hotelId\":\"h1\",\"guestName\":\"Ada\"}}");
            assertThat(client.booking("b1")).contains(new ReservationClient.BookingState("CANCELLED", "h1"));
            assertThat(client.booking("missing")).isEmpty();
            s.reply("/internal/bookings/b2", 500, "{\"status\":false,\"message\":\"boom\"}");
            assertThat(client.booking("b2")).contains(new ReservationClient.BookingState(null, null));
        }
    }

    @Test
    void orderDueReadsItsFieldsAndIsUnknownWhenFnbIsDown() {
        try (StubServer s = new StubServer()) {
            FnbClient client = new FnbClient(s.url(), StubServer.jwt(), KEY);
            s.reply("/internal/orders/o1/due", 200,
                    "{\"status\":true,\"data\":{\"hotelId\":\"h1\",\"number\":\"K-12\",\"status\":\"SERVED\",\"settled\":true,\"due\":1500.50,\"desk\":\"BAR\",\"x\":1}}");
            assertThat(client.order("o1")).contains(new FnbClient.OrderDue("h1", "K-12", "SERVED", true, new BigDecimal("1500.50"), "BAR"));
            s.reply("/internal/orders/o2/due", 200, "{\"status\":true,\"data\":{\"hotelId\":\"h1\"}}");
            assertThat(client.order("o2")).contains(new FnbClient.OrderDue("h1", null, null, false, null, null));
            assertThat(client.order("missing")).isEmpty();
        }
        assertThat(new FnbClient(StubServer.downUrl(), StubServer.jwt(), KEY).order("o1")).contains(new FnbClient.OrderDue(null, null, null, false, null, null));
    }

    @Test
    void folioBookingAndBusinessName() {
        try (StubServer s = new StubServer()) {
            FolioClient folio = new FolioClient(s.url(), StubServer.jwt(), KEY);
            TenantClient tenant = new TenantClient(s.url(), StubServer.jwt(), KEY);
            s.reply("/internal/folios/f1", 200, "{\"status\":true,\"data\":{\"id\":\"f1\",\"bookingId\":\"b1\"}}");
            s.reply("/internal/folios/f2", 200, "{\"status\":true,\"data\":{\"id\":\"f2\",\"bookingId\":\"\"}}");
            s.reply("/internal/businesses/biz", 200, "{\"status\":true,\"data\":{\"id\":\"biz\",\"name\":\"Eko Suites\"}}");
            assertThat(folio.bookingOf("f1")).contains("b1");
            assertThat(folio.bookingOf("f2")).isEmpty();
            assertThat(folio.bookingOf("missing")).isEmpty();
            assertThat(tenant.businessName("biz")).contains("Eko Suites");
            assertThat(tenant.businessName("missing")).isEmpty();
        }
    }
}
