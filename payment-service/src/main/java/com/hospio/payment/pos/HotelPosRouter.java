package com.hospio.payment.pos;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hospio.common.integrations.PosProvider.Outcome;
import com.hospio.common.integrations.PosProvider.WebhookResult;
import java.math.BigDecimal;
import java.util.Locale;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Sends a hotel's card payments to its own POS provider with its own keys (docs/pos-providers.md). A hotel without keys isn't
 * handled here: payment-service keeps using the server's POS provider for it (POS_PROVIDER), as before.
 *
 * <p>Our reference at the provider is the payment's id without dashes (32 characters): OPay's outOrderNo, Moniepoint's
 * merchantReference. OPay answers its own orderNo, kept as the payment's provider reference; Moniepoint's is our reference.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HotelPosRouter {

    private final HotelPosService hotels;
    private final OpayApi opay;
    private final MoniepointApi moniepoint;
    private final ObjectMapper mapper;

    /** True when the hotel has complete keys for a provider: its payments go through here. */
    public boolean handles(String hotelId) {
        return hotelId != null && hotels.credentials(hotelId).isPresent();
    }

    public boolean anyConfigured() {
        return !hotels.all(HotelPosService.OPAY).isEmpty() || !hotels.all(HotelPosService.MONIEPOINT).isEmpty();
    }

    public static String ourRef(String paymentId) {
        return paymentId.replace("-", "");
    }

    /** The payment id our reference stands for (dashes put back), or null when it isn't one of ours. */
    public static String paymentId(String ref) {
        if (ref == null || ref.length() != 32 || !ref.matches("[0-9a-fA-F]{32}")) {
            return null;
        }
        String r = ref.toLowerCase(Locale.ROOT);
        return r.substring(0, 8) + "-" + r.substring(8, 12) + "-" + r.substring(12, 16) + "-" + r.substring(16, 20) + "-" + r.substring(20);
    }

    /** Pushes the amount to the terminal; answers the provider reference to keep on the payment. */
    public String requestPayment(String hotelId, String terminalSerial, String paymentId, BigDecimal naira) {
        HotelPosService.Credentials c = hotels.credentials(hotelId).orElseThrow(() -> new IllegalStateException("No POS keys for this hotel"));
        String ref = ourRef(paymentId);
        if (HotelPosService.OPAY.equals(c.provider())) {
            OpayApi.Answer<ProviderDtos.OpayCreateOrderData> a = opay.createOrder(c.opay(), ref, naira, terminalSerial);
            if (!a.ok() || a.data() == null || a.data().orderNo() == null) {
                throw new IllegalStateException("OPay refused the payment: " + a.code() + " " + a.message());
            }
            return a.data().orderNo();
        }
        moniepoint.push(c.moniepoint(), terminalSerial, naira, ref);
        return ref;
    }

    /** Asks the provider how the payment went; empty when it can't say yet. */
    public Optional<WebhookResult> query(String hotelId, String paymentId, String providerRef) {
        HotelPosService.Credentials c = hotels.credentials(hotelId).orElse(null);
        if (c == null) {
            return Optional.empty();
        }
        if (HotelPosService.OPAY.equals(c.provider())) {
            OpayApi.Answer<ProviderDtos.OpayOrderData> a = opay.queryOrder(c.opay(), providerRef, providerRef == null ? ourRef(paymentId) : null);
            if (!a.ok() || a.data() == null) {
                return Optional.empty();
            }
            return Optional.of(new WebhookResult(a.data().orderNo() == null ? providerRef : a.data().orderNo(), paymentId,
                    opayOutcome(a.data().status()), a.data().amount(), a.data().status()));
        }
        ProviderDtos.MoniepointStatus s = moniepoint.status(c.moniepoint(), ourRef(paymentId));
        if (s == null) {
            return Optional.empty();
        }
        return Optional.of(new WebhookResult(providerRef, paymentId, moniepointOutcome(s.processingStatus(), s.responseCode()),
                kobo(s.actualAmount() != null ? s.actualAmount() : s.requestAmount()), s.processingStatus()));
    }

    // ---- webhooks ------------------------------------------------------------------------------------

    /**
     * OPay's webhook: each OPay hotel's keys are tried until one checks the signature and opens the payload (each hotel has its own
     * merchant keys). Empty when none does: the body isn't trusted.
     */
    public Optional<WebhookResult> opayWebhook(String rawBody) {
        ProviderDtos.OpayWebhookEnvelope env;
        try {
            env = mapper.readValue(rawBody, ProviderDtos.OpayWebhookEnvelope.class);
        } catch (Exception e) {
            log.warn("OPay webhook body unreadable: {}", e.getMessage());
            return Optional.empty();
        }
        for (HotelPosService.Credentials c : hotels.all(HotelPosService.OPAY)) {
            ProviderDtos.OpayOrderData d = opay.openWebhook(c.opay(), env);
            if (d != null) {
                return Optional.of(new WebhookResult(d.orderNo(), paymentId(d.outOrderNo()), opayOutcome(d.status()), d.amount(), d.status()));
            }
        }
        log.warn("OPay webhook not opened by any hotel's keys (signature or key mismatch)");
        return Optional.empty();
    }

    /**
     * Moniepoint's webhook: the payment is found from merchantReference, and the signature checked with that hotel's webhook secret.
     * Empty when the signature doesn't match or the payment isn't ours.
     */
    public Optional<WebhookResult> moniepointWebhook(String rawBody, String webhookId, String timestamp, String signature,
                                                     java.util.function.Function<String, String> hotelOfPayment) {
        ProviderDtos.MoniepointWebhook w;
        try {
            w = mapper.readValue(rawBody, ProviderDtos.MoniepointWebhook.class);
        } catch (Exception e) {
            log.warn("Moniepoint webhook body unreadable: {}", e.getMessage());
            return Optional.empty();
        }
        if (w.data() == null) {
            return Optional.empty();
        }
        String paymentId = paymentId(w.data().merchantReference());
        String hotelId = paymentId == null ? null : hotelOfPayment.apply(paymentId);
        HotelPosService.Credentials c = hotelId == null ? null : hotels.credentials(hotelId).orElse(null);
        if (c == null || !HotelPosService.MONIEPOINT.equals(c.provider())) {
            log.warn("Moniepoint webhook for {} isn't one of ours", w.data().merchantReference());
            return Optional.empty();
        }
        if (!MoniepointApi.signatureOk(c.values().get("webhookSecret"), webhookId, timestamp, rawBody, signature)) {
            log.warn("Moniepoint webhook signature didn't match for payment {}", paymentId);
            return Optional.empty();
        }
        String st = w.data().transactionStatus() == null ? "" : w.data().transactionStatus().toUpperCase(Locale.ROOT);
        Outcome outcome = switch (st) {
            case "APPROVED", "SUCCESSFUL" -> w.data().responseCode() == null || "00".equals(w.data().responseCode()) ? Outcome.APPROVED : Outcome.DECLINED;
            case "PENDING" -> Outcome.UNKNOWN;
            default -> Outcome.DECLINED;
        };
        return Optional.of(new WebhookResult(w.data().merchantReference(), paymentId, outcome, kobo(w.data().amount()), st));
    }

    // ---- mapping -------------------------------------------------------------------------------------

    /** OPay's status strings (StationMag: "best guess", to confirm on the first real payment). */
    static Outcome opayOutcome(String status) {
        String s = status == null ? "" : status.toUpperCase(Locale.ROOT);
        return switch (s) {
            case "SUCCESS", "SUCCESSFUL", "COMPLETED" -> Outcome.APPROVED;
            case "FAIL", "FAILED", "CLOSED" -> Outcome.DECLINED;
            default -> Outcome.UNKNOWN;
        };
    }

    static Outcome moniepointOutcome(String processingStatus, String responseCode) {
        String s = processingStatus == null ? "" : processingStatus.toUpperCase(Locale.ROOT);
        return switch (s) {
            case "PROCESSED" -> "00".equals(responseCode) ? Outcome.APPROVED : Outcome.DECLINED;
            case "CANCELLED", "EXPIRED" -> Outcome.DECLINED;
            default -> Outcome.UNKNOWN;
        };
    }

    static BigDecimal kobo(Long kobo) {
        return kobo == null ? null : BigDecimal.valueOf(kobo).movePointLeft(2);
    }
}
