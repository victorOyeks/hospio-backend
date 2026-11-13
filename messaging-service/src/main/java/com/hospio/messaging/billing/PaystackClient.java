package com.hospio.messaging.billing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hospio.common.exception.BadRequestException;
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
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Paystack, for paying guest-message invoices (docs/guest-message-billing.md): start a checkout
 * (POST /transaction/initialize → the page to send the payer to), confirm it (GET /transaction/verify/{reference}), and
 * check a webhook's signature (HMAC-SHA512 of the body with the secret key). Amounts are in kobo.
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
    private final String secretKey;
    private final String baseUrl;

    public PaystackClient(@Value("${hospio.paystack.secret-key:}") String secretKey,
                          @Value("${hospio.paystack.base-url:https://api.paystack.co}") String baseUrl) {
        this.secretKey = secretKey == null ? "" : secretKey.trim();
        this.baseUrl = baseUrl.replaceAll("/+$", "");
    }

    public boolean configured() {
        return StringUtils.hasText(secretKey);
    }

    public Checkout initialize(String email, BigDecimal amount, String reference, String callbackUrl, Map<String, Object> metadata) {
        if (!configured()) {
            throw new BadRequestException("Online payment isn't set up yet. Pay by transfer and Hospio360 will mark the invoice paid");
        }
        long kobo = amount.setScale(2, RoundingMode.HALF_UP).movePointRight(2).longValueExact();
        Map<String, Object> body = new java.util.HashMap<>(Map.of("email", email, "amount", kobo, "reference", reference, "currency", "NGN",
                "metadata", metadata));
        if (StringUtils.hasText(callbackUrl)) {
            body.put("callback_url", callbackUrl);
        }
        JsonNode res = call("POST", "/transaction/initialize", body);
        String url = res.path("data").path("authorization_url").asText(null);
        if (url == null) {
            throw new BadRequestException("Paystack could not start the payment: " + res.path("message").asText("no answer"));
        }
        return new Checkout(url, res.path("data").path("reference").asText(reference));
    }

    public Verified verify(String reference) {
        if (!configured()) {
            return new Verified(false, BigDecimal.ZERO, null, "Online payment isn't set up");
        }
        JsonNode d = call("GET", "/transaction/verify/" + URLEncoder.encode(reference, StandardCharsets.UTF_8), null).path("data");
        boolean ok = "success".equalsIgnoreCase(d.path("status").asText());
        BigDecimal naira = BigDecimal.valueOf(d.path("amount").asLong(0)).movePointLeft(2);
        return new Verified(ok, naira, d.path("channel").asText(null), d.path("gateway_response").asText(null));
    }

    /** Whether a webhook body really comes from Paystack (x-paystack-signature). */
    public boolean signed(String body, String signature) {
        if (!configured() || signature == null || body == null) {
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

    private JsonNode call(String method, String path, Object body) {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(baseUrl + path)).timeout(Duration.ofSeconds(20))
                    .header("Authorization", "Bearer " + secretKey).header("Accept", "application/json");
            if ("POST".equals(method)) {
                b.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
            } else {
                b.GET();
            }
            HttpResponse<String> res = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
            JsonNode root = json.readTree(res.body() == null || res.body().isBlank() ? "{}" : res.body());
            if (res.statusCode() >= 300) {
                log.warn("Paystack {} {}: HTTP {} {}", method, path, res.statusCode(), root.path("message").asText(""));
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
