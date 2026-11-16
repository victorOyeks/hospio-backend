package com.hospio.messaging.billing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hospio.common.exception.BadRequestException;
import com.hospio.common.integrations.settings.IntegrationSettings;
import com.hospio.common.integrations.settings.IntegrationSettingsReader;
import com.hospio.common.integrations.settings.IntegrationTestResult;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Paystack, for paying guest-message invoices (docs/guest-message-billing.md): start a checkout
 * (POST /transaction/initialize → the page to send the payer to), confirm it (GET /transaction/verify/{reference}), and
 * check a webhook's signature (HMAC-SHA512 of the body with the secret key). Amounts are in kobo. The secret key saved in the admin
 * portal (Settings · Integrations · Paystack) wins over PAYSTACK_SECRET_KEY, read per call (cached a minute).
 */
@Slf4j
@Component
public class PaystackClient {

    /** What Paystack says about a payment: done or not, how much (naira), and how it was paid. */
    public record Verified(boolean success, BigDecimal amount, String channel, String gatewayResponse) {
    }

    public record Checkout(String authorizationUrl, String reference) {
    }

    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final String envKey;
    private final String baseUrl;
    private IntegrationSettingsReader integrations;

    public PaystackClient(@Value("${hospio.paystack.secret-key:}") String secretKey,
                          @Value("${hospio.paystack.base-url:https://api.paystack.co}") String baseUrl) {
        this.envKey = secretKey;
        this.baseUrl = baseUrl.replaceAll("/+$", "");
    }

    @Autowired(required = false)
    public void setIntegrations(IntegrationSettingsReader integrations) {
        this.integrations = integrations;
    }

    /** The secret key in force: the portal's, else PAYSTACK_SECRET_KEY. */
    private String secretKey() {
        IntegrationSettings.Paystack env = new IntegrationSettings.Paystack(envKey);
        String k = (integrations == null ? env : integrations.effective(env)).secretKey();
        return k == null ? "" : k.trim();
    }

    public boolean configured() {
        return StringUtils.hasText(secretKey());
    }

    /** The admin portal's test, with the key in force: GET /balance. Charges nothing. */
    public IntegrationTestResult check() {
        if (!configured()) {
            return IntegrationTestResult.failed("There is no Paystack secret key, saved here or on the server");
        }
        try {
            PaystackDtos.BalanceResponse res = call("GET", "/balance", null, PaystackDtos.BalanceResponse.class);
            if (!Boolean.TRUE.equals(res.status())) {
                return IntegrationTestResult.failed("Paystack refused the key: " + (res.message() == null ? "no reason given" : res.message()));
            }
            String balances = res.data() == null || res.data().isEmpty() ? "no balance lines"
                    : res.data().stream().map(b -> (b.currency() == null ? "" : b.currency() + " ")
                    + BigDecimal.valueOf(b.balance() == null ? 0 : b.balance()).movePointLeft(2).toPlainString())
                    .collect(java.util.stream.Collectors.joining(", "));
            boolean test = secretKey().startsWith("sk_test_");
            return IntegrationTestResult.ok("Paystack accepted the key (" + (test ? "test" : "live") + " mode): balance " + balances);
        } catch (BadRequestException e) {
            return IntegrationTestResult.failed(e.getMessage());
        }
    }

    public Checkout initialize(String email, BigDecimal amount, String reference, String callbackUrl, PaystackDtos.Metadata metadata) {
        if (!configured()) {
            throw new BadRequestException("Online payment isn't set up yet. Pay by transfer and Hospio360 will mark the invoice paid");
        }
        long kobo = amount.setScale(2, RoundingMode.HALF_UP).movePointRight(2).longValueExact();
        PaystackDtos.InitializeRequest body = new PaystackDtos.InitializeRequest(email, kobo, reference, "NGN", metadata,
                StringUtils.hasText(callbackUrl) ? callbackUrl : null);
        PaystackDtos.InitializeResponse res = call("POST", "/transaction/initialize", body, PaystackDtos.InitializeResponse.class);
        String url = res.data() == null ? null : res.data().authorizationUrl();
        if (url == null) {
            throw new BadRequestException("Paystack could not start the payment: " + (res.message() == null ? "no answer" : res.message()));
        }
        return new Checkout(url, res.data().reference() == null ? reference : res.data().reference());
    }

    public Verified verify(String reference) {
        if (!configured()) {
            return new Verified(false, BigDecimal.ZERO, null, "Online payment isn't set up");
        }
        PaystackDtos.VerifyResponse res = call("GET", "/transaction/verify/" + URLEncoder.encode(reference, StandardCharsets.UTF_8), null,
                PaystackDtos.VerifyResponse.class);
        PaystackDtos.Transaction d = res.data() == null ? new PaystackDtos.Transaction(null, null, null, null, null) : res.data();
        boolean ok = "success".equalsIgnoreCase(d.status());
        BigDecimal naira = BigDecimal.valueOf(d.amount() == null ? 0 : d.amount()).movePointLeft(2);
        return new Verified(ok, naira, d.channel(), d.gatewayResponse());
    }

    /** Whether a webhook body really comes from Paystack (x-paystack-signature). */
    public boolean signed(String body, String signature) {
        String secretKey = secretKey();
        if (!StringUtils.hasText(secretKey) || signature == null || body == null) {
            return false;
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA512");
            mac.init(new SecretKeySpec(secretKey.getBytes(StandardCharsets.UTF_8), "HmacSHA512"));
            String expected = HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
            return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), signature.trim().toLowerCase().getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            return false;
        }
    }

    /** Paystack's answer in {@code type}; a refusal (HTTP 4xx/5xx) is logged with its message and still returned. */
    private <T extends PaystackDtos.Answer> T call(String method, String path, Object body, Class<T> type) {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(baseUrl + path)).timeout(Duration.ofSeconds(20))
                    .header("Authorization", "Bearer " + secretKey()).header("Accept", "application/json");
            if ("POST".equals(method)) {
                b.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
            } else {
                b.GET();
            }
            HttpResponse<String> res = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
            T root = json.readValue(res.body() == null || res.body().isBlank() ? "{}" : res.body(), type);
            if (res.statusCode() >= 300) {
                log.warn("Paystack {} {}: HTTP {} {}", method, path, res.statusCode(), root.message() == null ? "" : root.message());
            }
            return root;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BadRequestException("Paystack could not be reached");
        } catch (BadRequestException e) {
            throw e;
        } catch (Exception e) {
            throw new BadRequestException("Paystack could not be reached: " + e.getClass().getSimpleName());
        }
    }
}
