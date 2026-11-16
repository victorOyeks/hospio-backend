package com.hospio.messaging.billing;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Paystack's JSON (docs/guest-message-billing.md): {@code POST /transaction/initialize}, {@code GET /transaction/verify/{reference}}
 * and the {@code charge.success} webhook. Amounts are in kobo.
 */
public final class PaystackDtos {

    private PaystackDtos() {
    }

    /** Every Paystack answer: {@code status} and {@code message}, then {@code data} in the endpoint's own shape. */
    public interface Answer {
        Boolean status();

        String message();
    }

    /** {@code {"email","amount","reference","currency":"NGN","metadata":{…},"callback_url"}}: callback_url only when there is one. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record InitializeRequest(String email, long amount, String reference, String currency, Metadata metadata,
                                    @JsonProperty("callback_url") String callbackUrl) {
    }

    /** What the invoice payment carries in Paystack's metadata (seen on the dashboard and in the webhook). */
    public record Metadata(@JsonProperty("invoice_id") String invoiceId, @JsonProperty("invoice_number") String invoiceNumber,
                           @JsonProperty("hotel_id") String hotelId, @JsonProperty("paid_by") String paidBy) {
    }

    /** {@code {"status":true,"message":"Authorization URL created","data":{"authorization_url","access_code","reference"}}} */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record InitializeResponse(Boolean status, String message, Authorization data) implements Answer {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Authorization(@JsonProperty("authorization_url") String authorizationUrl, @JsonProperty("access_code") String accessCode,
                                String reference) {
    }

    /** {@code {"status":true,"message":"Verification successful","data":{"status":"success","amount":…,"channel":"card",…}}} */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record VerifyResponse(Boolean status, String message, Transaction data) implements Answer {
    }

    /** A transaction as Paystack's verify reports it: {@code status} success/failed/abandoned, {@code amount} in kobo. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Transaction(String status, String reference, Long amount, String channel, @JsonProperty("gateway_response") String gatewayResponse) {
    }

    /** {@code {"event":"charge.success","data":{"reference":…,…}}} */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record WebhookEvent(String event, WebhookData data) {
    }

    /** Only the reference is read (other events' data differs in shape and is ignored). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record WebhookData(String reference) {
    }

    /** {@code GET /balance}: one line per currency, amounts in kobo (the admin portal's test). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record BalanceResponse(Boolean status, String message, java.util.List<Balance> data) implements Answer {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Balance(String currency, Long balance) {
    }
}
