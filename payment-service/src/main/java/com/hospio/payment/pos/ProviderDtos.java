package com.hospio.payment.pos;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;

/** OPay's and Moniepoint's request and answer shapes (docs/pos-providers.md). Unknown fields are ignored: providers add fields. */
public final class ProviderDtos {

    private ProviderDtos() {
    }

    // ---- OPay ----------------------------------------------------------------------------------------

    /** Push to terminal; amount in naira as a plain string. Null fields are left out (the JSON is sorted and encrypted). */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record OpayCreateOrder(String headMerchantId, String merchantId, String outOrderNo, String amount, String currency, Long orderExpireTime,
                                  String isSplit, String sceneEnum, String subSceneEnum, String sn) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record OpayQueryOrder(String headMerchantId, String merchantId, String orderNo, String outOrderNo) {
    }

    /** The body every OPay call sends. */
    public record OpayRequest(String paramContent, String sign) {
    }

    /** Every OPay answer; {@code data} is base64 RSA ciphertext of the call's own data. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record OpayEnvelope(String code, String message, String data, String sign) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record OpayCreateOrderData(String orderNo) {
    }

    /** Query order and the webhook payload: amount in naira. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record OpayOrderData(String outOrderNo, String orderNo, String status, BigDecimal amount, String sn, String payType,
                                String senderName, String senderAccount, String transactionTime, String completedTime) {
    }

    /** What OPay posts to the webhook: the payload is encrypted in {@code paramContent}, signed with {@code sign}. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record OpayWebhookEnvelope(String clientAuthKey, String version, String bodyFormat, String timestamp, String paramContent, String sign) {
    }

    /** The webhook answer OPay expects, whatever happened. */
    public record OpayAck(String code, String message) {
        public static final OpayAck OK = new OpayAck("00000", "SUCCESSFUL");
    }

    // ---- Moniepoint ----------------------------------------------------------------------------------

    /** Push to terminal; amount in kobo. */
    public record MoniepointPush(String terminalSerial, long amount, String merchantReference, String transactionType, String paymentMethod) {
    }

    /** Transaction status by our reference: amounts in kobo. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MoniepointStatus(String merchantReference, String transactionReference, String terminalSerial, String processingStatus,
                                   String responseCode, String responseMessage, Long requestAmount, Long actualAmount) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MoniepointWebhook(String eventId, String eventType, String createdAt, MoniepointWebhookData data) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MoniepointWebhookData(Long amount, String responseCode, String responseMessage, String terminalSerial, String merchantReference,
                                        String transactionStatus, String transactionReference) {
    }
}
