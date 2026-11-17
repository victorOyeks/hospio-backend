package com.hospio.payment.pos;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import com.hospio.common.integrations.settings.IntegrationSettings;
import com.hospio.common.integrations.settings.IntegrationSettingsReader;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * Moniepoint's push-payment API (docs/pos-providers.md, ported from StationMag): {@code Authorization: Bearer <hotel's API key>},
 * amounts in kobo. The base URL is server-wide: saved in the admin portal (Settings · Integrations · Moniepoint POS), else
 * MONIEPOINT_BASE_URL, read per call; each hotel's API key and webhook secret are passed in.
 */
@Slf4j
@Component
public class MoniepointApi {

    public record Keys(String apiKey, String webhookSecret) {
    }

    /** A client for one base URL, rebuilt only when the URL in force changes. */
    private record Client(String baseUrl, RestClient http) {
    }

    private final String envUrl;
    private IntegrationSettingsReader integrations;
    private volatile Client client;

    public MoniepointApi(@Value("${hospio.pos.moniepoint.base-url:}") String baseUrl) {
        this.envUrl = baseUrl;
    }

    @Autowired(required = false)
    public void setIntegrations(IntegrationSettingsReader integrations) {
        this.integrations = integrations;
    }

    /** The base URL in force: the portal's, else MONIEPOINT_BASE_URL ("" when neither). */
    public String baseUrl() {
        IntegrationSettings.Moniepoint env = new IntegrationSettings.Moniepoint(envUrl);
        String url = (integrations == null ? env : integrations.effective(env)).baseUrl();
        return url == null ? "" : url.trim();
    }

    private RestClient http() {
        String url = baseUrl();
        Client c = client;
        if (c == null || !c.baseUrl().equals(url)) {
            SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
            f.setConnectTimeout((int) Duration.ofSeconds(15).toMillis());
            f.setReadTimeout((int) Duration.ofSeconds(30).toMillis());
            c = new Client(url, RestClient.builder().baseUrl(url.isEmpty() ? "http://moniepoint.invalid" : url).requestFactory(f).build());
            client = c;
        }
        return c.http();
    }

    public boolean configured() {
        return !baseUrl().isEmpty();
    }

    /**
     * Push to terminal; any 2xx means accepted. The reference is ours (merchantReference). A 4xx is Moniepoint refusing the request
     * (unknown terminal, bad amount, wrong key): an IllegalStateException with Moniepoint's words, so the payment fails with them and
     * the terminal isn't marked offline. Anything else (5xx, timeout) is thrown as it is. Every call is logged, never the key.
     */
    public void push(Keys k, String terminalSerial, BigDecimal naira, String merchantReference) {
        requireUrl();
        RestClient http = http();
        ProviderDtos.MoniepointPush body = new ProviderDtos.MoniepointPush(terminalSerial, kobo(naira), merchantReference, "PURCHASE", "ANY");
        log.info("Moniepoint push -> POST {}/v1/transactions terminal {} amount {} kobo ref {}", baseUrl(), terminalSerial, body.amount(), merchantReference);
        long started = System.currentTimeMillis();
        try {
            var res = http.post().uri("/v1/transactions").header("Authorization", "Bearer " + k.apiKey()).body(body).retrieve().toEntity(String.class);
            log.info("Moniepoint push <- {} in {} ms ref {}: {}", res.getStatusCode().value(), System.currentTimeMillis() - started, merchantReference,
                    oneLine(res.getBody()));
        } catch (RestClientResponseException e) {
            log.warn("Moniepoint push <- {} in {} ms ref {}: {}", e.getStatusCode().value(), System.currentTimeMillis() - started, merchantReference,
                    oneLine(e.getResponseBodyAsString()));
            if (e.getStatusCode().is4xxClientError()) {
                throw new IllegalStateException("Moniepoint refused the payment: " + refusal(e), e);
            }
            throw e;
        } catch (RuntimeException e) {
            log.warn("Moniepoint push failed after {} ms ref {}: {}", System.currentTimeMillis() - started, merchantReference, e.toString());
            throw e;
        }
    }

    /** The transaction by our reference; null when Moniepoint hasn't seen it (404). */
    public ProviderDtos.MoniepointStatus status(Keys k, String merchantReference) {
        requireUrl();
        long started = System.currentTimeMillis();
        try {
            ProviderDtos.MoniepointStatus s = http().get().uri("/v1/transactions/merchants/{ref}", merchantReference)
                    .header("Authorization", "Bearer " + k.apiKey()).retrieve().body(ProviderDtos.MoniepointStatus.class);
            log.info("Moniepoint status ref {} <- in {} ms: {}", merchantReference, System.currentTimeMillis() - started, s == null ? "no body"
                    : "status " + s.processingStatus() + ", code " + s.responseCode() + ", message " + s.responseMessage() + ", txn "
                    + s.transactionReference() + ", amount " + s.actualAmount() + "/" + s.requestAmount() + " kobo");
            return s;
        } catch (HttpClientErrorException.NotFound e) {
            log.info("Moniepoint status ref {} <- 404 in {} ms (not seen yet)", merchantReference, System.currentTimeMillis() - started);
            return null;
        } catch (RestClientResponseException e) {
            log.warn("Moniepoint status ref {} <- {} in {} ms: {}", merchantReference, e.getStatusCode().value(), System.currentTimeMillis() - started,
                    oneLine(e.getResponseBodyAsString()));
            throw e;
        } catch (RuntimeException e) {
            log.warn("Moniepoint status ref {} failed after {} ms: {}", merchantReference, System.currentTimeMillis() - started, e.toString());
            throw e;
        }
    }

    /** Moniepoint's own words from an error body ({"message": ...} or similar), else the HTTP status. */
    private static String refusal(RestClientResponseException e) {
        String body = e.getResponseBodyAsString();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"(?:message|responseMessage|error_description|error)\"\\s*:\\s*\"([^\"]{1,200})\"")
                .matcher(body == null ? "" : body);
        return m.find() ? m.group(1) : "HTTP " + e.getStatusCode().value();
    }

    /** A response body on one line, at most 500 characters: Moniepoint's answers carry no secrets, only the transaction. */
    static String oneLine(String body) {
        if (body == null || body.isBlank()) {
            return "(empty)";
        }
        String s = body.replaceAll("\\s*\\n\\s*", " ").trim();
        return s.length() > 500 ? s.substring(0, 500) + "…" : s;
    }

    /** {@code signature == base64(HmacSHA256(secret, id + "__" + timestamp + "__" + rawBody))}, compared in constant time. */
    public static boolean signatureOk(String secret, String webhookId, String timestamp, String rawBody, String signature) {
        if (secret == null || secret.isBlank() || signature == null || webhookId == null || timestamp == null) {
            return false;
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] computed = Base64.getEncoder().encode(mac.doFinal((webhookId + "__" + timestamp + "__" + rawBody).getBytes(StandardCharsets.UTF_8)));
            return MessageDigest.isEqual(computed, signature.trim().getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            return false;
        }
    }

    static long kobo(BigDecimal naira) {
        return naira.movePointRight(2).setScale(0, java.math.RoundingMode.HALF_UP).longValueExact();
    }

    private void requireUrl() {
        if (baseUrl().isEmpty()) {
            throw new IllegalStateException("MONIEPOINT_BASE_URL isn't set on the server");
        }
    }
}
