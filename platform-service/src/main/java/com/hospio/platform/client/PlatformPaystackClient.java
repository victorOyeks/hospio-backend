package com.hospio.platform.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hospio.common.exception.BadRequestException;
import com.hospio.common.integrations.settings.IntegrationSettings;
import com.hospio.common.integrations.settings.IntegrationCatalogue;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Paystack for device invoices (docs/plan-device-packages.md): start a checkout and confirm it by reference. The secret key is the
 * one saved in Settings · Integrations, else PAYSTACK_SECRET_KEY (the same as guest-message invoices). Amounts are in kobo.
 */
@Slf4j
@Component
public class PlatformPaystackClient {

    public record Checkout(String authorizationUrl, String reference) {
    }

    public record Verified(boolean success, BigDecimal amount, String message) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record InitializeRequest(String email, long amount, String reference, String currency, @JsonProperty("callback_url") String callbackUrl) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Answer(Boolean status, String message, Data data) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Data(@JsonProperty("authorization_url") String authorizationUrl, String reference, String status, Long amount,
                @JsonProperty("gateway_response") String gatewayResponse) {
    }

    private final String envKey;
    private final String baseUrl;
    private final ObjectMapper json;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private com.hospio.platform.service.IntegrationSettingsService integrations;

    public PlatformPaystackClient(@Value("${hospio.paystack.secret-key:}") String secretKey,
                                  @Value("${hospio.paystack.base-url:https://api.paystack.co}") String baseUrl, ObjectMapper json) {
        this.envKey = secretKey;
        this.baseUrl = baseUrl;
        this.json = json;
    }

    @Autowired(required = false)
    public void setIntegrations(com.hospio.platform.service.IntegrationSettingsService integrations) {
        this.integrations = integrations;
    }

    /** The key saved in Settings · Integrations (platform-service owns them), else PAYSTACK_SECRET_KEY. */
    private String secretKey() {
        IntegrationSettings.Paystack env = new IntegrationSettings.Paystack(envKey);
        IntegrationSettings.Paystack saved = null;
        if (integrations != null) {
            try {
                IntegrationSettings s = integrations.saved("PAYSTACK");
                saved = s instanceof IntegrationSettings.Paystack p ? p : null;
            } catch (RuntimeException e) {
                log.warn("Saved Paystack settings couldn't be read, using the environment: {}", e.getMessage());
            }
        }
        String k = IntegrationCatalogue.overlay(saved, env).secretKey();
        return k == null ? "" : k.trim();
    }

    public boolean configured() {
        return StringUtils.hasText(secretKey());
    }

    public Checkout initialize(String email, BigDecimal amount, String reference, String callbackUrl) {
        if (!configured()) {
            throw new BadRequestException("Online payment isn't set up yet. Pay by transfer and Hospio360 will mark the invoice paid");
        }
        long kobo = amount.setScale(2, RoundingMode.HALF_UP).movePointRight(2).longValueExact();
        Answer a = call("POST", "/transaction/initialize", new InitializeRequest(email, kobo, reference, "NGN", StringUtils.hasText(callbackUrl) ? callbackUrl : null));
        if (a.data() == null || a.data().authorizationUrl() == null) {
            throw new BadRequestException("Paystack could not start the payment: " + (a.message() == null ? "no answer" : a.message()));
        }
        return new Checkout(a.data().authorizationUrl(), a.data().reference() == null ? reference : a.data().reference());
    }

    public Verified verify(String reference) {
        if (!configured()) {
            return new Verified(false, BigDecimal.ZERO, "Online payment isn't set up");
        }
        Answer a = call("GET", "/transaction/verify/" + URLEncoder.encode(reference, StandardCharsets.UTF_8), null);
        Data d = a.data();
        boolean ok = d != null && "success".equalsIgnoreCase(d.status());
        BigDecimal naira = BigDecimal.valueOf(d == null || d.amount() == null ? 0 : d.amount()).movePointLeft(2);
        return new Verified(ok, naira, d == null ? a.message() : d.gatewayResponse());
    }

    private Answer call(String method, String path, Object body) {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(baseUrl + path)).timeout(Duration.ofSeconds(20))
                    .header("Authorization", "Bearer " + secretKey()).header("Accept", "application/json");
            if ("POST".equals(method)) {
                b.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
            } else {
                b.GET();
            }
            HttpResponse<String> res = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
            Answer a = json.readValue(res.body() == null || res.body().isBlank() ? "{}" : res.body(), Answer.class);
            log.info("Paystack {} {} <- HTTP {} {}", method, path, res.statusCode(), a.message() == null ? "" : a.message());
            return a;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BadRequestException("Paystack could not be reached");
        } catch (Exception e) {
            throw new BadRequestException("Paystack could not be reached: " + e.getClass().getSimpleName());
        }
    }
}
