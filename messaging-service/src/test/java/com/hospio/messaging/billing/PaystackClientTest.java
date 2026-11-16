package com.hospio.messaging.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.hospio.common.exception.BadRequestException;
import com.hospio.messaging.StubHttp;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** docs/guest-message-billing.md: Paystack's initialize and verify in its documented JSON, and its signed webhook. */
class PaystackClientTest {

    private StubHttp server;
    private PaystackClient paystack;

    @BeforeEach
    void start() throws Exception {
        server = new StubHttp();
        paystack = new PaystackClient("sk_test_1", server.url());
    }

    @AfterEach
    void stop() {
        server.close();
    }

    private static final PaystackDtos.Metadata META = new PaystackDtos.Metadata("inv-1", "MSG-202610-0001", "h-1", "Ada Obi");

    @Test
    void aCheckoutIsStartedInKoboWithTheInvoiceInItsMetadata() {
        server.on("/transaction/initialize", 200, """
                {"status":true,"message":"Authorization URL created","data":{"authorization_url":"https://checkout.paystack.com/0peioxfhpn",
                 "access_code":"0peioxfhpn","reference":"MSG-202610-0001-AAAAAAAA"}}""");
        PaystackClient.Checkout c = paystack.initialize("ada@example.com", new BigDecimal("1234.5"), "MSG-202610-0001-AAAAAAAA",
                "https://hotel.hospio360.com/back", META);
        assertThat(server.last().body()).isEqualTo("{\"email\":\"ada@example.com\",\"amount\":123450,\"reference\":\"MSG-202610-0001-AAAAAAAA\","
                + "\"currency\":\"NGN\",\"metadata\":{\"invoice_id\":\"inv-1\",\"invoice_number\":\"MSG-202610-0001\",\"hotel_id\":\"h-1\","
                + "\"paid_by\":\"Ada Obi\"},\"callback_url\":\"https://hotel.hospio360.com/back\"}");
        assertThat(c).isEqualTo(new PaystackClient.Checkout("https://checkout.paystack.com/0peioxfhpn", "MSG-202610-0001-AAAAAAAA"));
    }

    @Test
    void noCallbackMeansNoCallbackUrlAndARefusalSaysWhy() {
        server.on("/transaction/initialize", 400, "{\"status\":false,\"message\":\"Duplicate Transaction Reference\"}");
        assertThatThrownBy(() -> paystack.initialize("ada@example.com", BigDecimal.TEN, "R-1", null, META))
                .isInstanceOf(BadRequestException.class).hasMessage("Paystack could not start the payment: Duplicate Transaction Reference");
        assertThat(server.last().body()).doesNotContain("callback_url");
    }

    @Test
    void aVerifiedPaymentIsReadInNaira() {
        server.on("/transaction/verify/MSG-1", 200, """
                {"status":true,"message":"Verification successful","data":{"id":4099260516,"domain":"test","status":"success",
                 "reference":"MSG-1","amount":123450,"gateway_response":"Successful","paid_at":"2026-11-10T09:12:03.000Z","channel":"card",
                 "currency":"NGN","metadata":{"invoice_id":"inv-1"},"authorization":{"last4":"4081"},"customer":{"email":"ada@example.com"}}}""");
        assertThat(paystack.verify("MSG-1")).isEqualTo(new PaystackClient.Verified(true, new BigDecimal("1234.50"), "card", "Successful"));
        assertThat(server.last().method()).isEqualTo("GET");
    }

    @Test
    void anAbandonedOrUnknownPaymentIsNotSuccess() {
        server.on("/transaction/verify/MSG-2", 200, """
                {"status":true,"message":"Verification successful","data":{"status":"abandoned","reference":"MSG-2","amount":5000,
                 "gateway_response":"The transaction was not completed","channel":"card"}}""");
        assertThat(paystack.verify("MSG-2")).isEqualTo(new PaystackClient.Verified(false, new BigDecimal("50.00"), "card",
                "The transaction was not completed"));
        server.on("/transaction/verify/MSG-3", 400, "{\"status\":false,\"message\":\"Transaction reference not found\"}");
        assertThat(paystack.verify("MSG-3")).isEqualTo(new PaystackClient.Verified(false, new BigDecimal("0.00"), null, null));
    }

    // ---- the webhook -------------------------------------------------------------------------------------------------

    private static String sign(String body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA512");
        mac.init(new SecretKeySpec("sk_test_1".getBytes(StandardCharsets.UTF_8), "HmacSHA512"));
        return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void aSignedChargeSuccessSettlesItsInvoice() throws Exception {
        CommBilling billing = mock(CommBilling.class);
        CommBillingController controller = new CommBillingController(billing, paystack);
        String body = """
                {"event":"charge.success","data":{"id":302961,"domain":"live","status":"success","reference":"MSG-202610-0001-AAAAAAAA",
                 "amount":123450,"gateway_response":"Approved","channel":"card","metadata":{"invoice_id":"inv-1"},"customer":{"email":"a@b.c"}}}""";
        assertThat(controller.paystackWebhook(sign(body), body).getStatusCode().value()).isEqualTo(200);
        verify(billing).confirm("MSG-202610-0001-AAAAAAAA", "Paystack");
    }

    @Test
    void otherEventsOtherReferencesAndForgeriesSettleNothing() throws Exception {
        CommBilling billing = mock(CommBilling.class);
        CommBillingController controller = new CommBillingController(billing, paystack);
        String transfer = "{\"event\":\"transfer.success\",\"data\":{\"reference\":\"MSG-1\",\"recipient\":{\"name\":\"x\"}}}";
        String fee = "{\"event\":\"charge.success\",\"data\":{\"reference\":\"FEE-1\"}}";
        assertThat(controller.paystackWebhook(sign(transfer), transfer).getStatusCode().value()).isEqualTo(200);
        assertThat(controller.paystackWebhook(sign(fee), fee).getStatusCode().value()).isEqualTo(200);
        assertThat(controller.paystackWebhook("bad", fee).getStatusCode().value()).isEqualTo(401);
        verify(billing, never()).confirm(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
    }
}
