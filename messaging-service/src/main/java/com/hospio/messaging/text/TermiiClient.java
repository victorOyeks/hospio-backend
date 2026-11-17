package com.hospio.messaging.text;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hospio.common.integrations.settings.IntegrationSettings;
import com.hospio.common.integrations.settings.IntegrationSettingsReader;
import com.hospio.common.integrations.settings.IntegrationTestResult;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Termii, the SMS provider, used only for one-time codes (docs/sms-whatsapp.md); set up as CoRyd's notification-service does.
 * Codes go on Termii's {@code dnd} route from the approved sender ID ({@code TERMII_SENDER_ID}, Hospio360): "generic" is the
 * promotional route, which never reaches a number on Nigeria's DND list while Termii still says "sent". Switched on with
 * {@code TERMII_ENABLED}. Each setting can be saved in the admin portal (Settings · Integrations · Termii), which wins over the
 * environment and is read per call (cached a minute). Calls never throw.
 */
@Slf4j
@Component
public class TermiiClient {

    /** The settings as used for one call. */
    private record Account(boolean enabled, String baseUrl, String apiKey, String senderId) {
        /** Switched on, with a key and an approved sender ID: Termii refuses a send from an unregistered sender. */
        boolean configured() {
            return enabled && StringUtils.hasText(baseUrl) && StringUtils.hasText(apiKey) && StringUtils.hasText(senderId);
        }
    }

    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    /** TERMII_* from the environment; the portal's saved values are laid over them per call. */
    private final IntegrationSettings.Termii env;
    private final String channel;
    private IntegrationSettingsReader integrations;

    public TermiiClient(@Value("${hospio.termii.enabled:false}") boolean enabled,
                        @Value("${hospio.termii.base-url:https://api.ng.termii.com}") String baseUrl,
                        @Value("${hospio.termii.api-key:}") String apiKey,
                        @Value("${hospio.termii.sender-id:}") String senderId,
                        @Value("${hospio.termii.channel:dnd}") String channel) {
        this.env = new IntegrationSettings.Termii(enabled, baseUrl, apiKey, senderId, null);
        this.channel = StringUtils.hasText(channel) ? channel.trim() : "dnd";
    }

    @Autowired(required = false)
    public void setIntegrations(IntegrationSettingsReader integrations) {
        this.integrations = integrations;
    }

    private Account account() {
        IntegrationSettings.Termii in = integrations == null ? env : integrations.effective(env);
        return new Account(Boolean.TRUE.equals(in.enabled()), in.baseUrl() == null ? "" : in.baseUrl().trim().replaceAll("/+$", ""),
                in.apiKey() == null ? "" : in.apiKey().trim(), in.senderId() == null ? "" : in.senderId().trim());
    }

    /** Switched on, with a key and an approved sender ID: Termii refuses a send from an unregistered sender. */
    public boolean configured() {
        return account().configured();
    }

    /** What is in force, for the admin portal's connection checks: never the key itself, only whether there is one. */
    public record Setup(boolean enabled, boolean keyPresent, String senderId, String channel, boolean configured) {
    }

    public Setup setup() {
        Account a = account();
        return new Setup(a.enabled(), StringUtils.hasText(a.baseUrl()) && StringUtils.hasText(a.apiKey()), a.senderId(), channel, a.configured());
    }

    /** The admin portal's test, with the settings in force: the account balance (GET /api/get-balance). Sends nothing. */
    public IntegrationTestResult check() {
        Account a = account();
        if (!StringUtils.hasText(a.baseUrl()) || !StringUtils.hasText(a.apiKey())) {
            return IntegrationTestResult.failed("Termii needs a base URL and an API key, saved here or on the server");
        }
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(a.baseUrl() + "/api/get-balance?api_key="
                            + java.net.URLEncoder.encode(a.apiKey(), java.nio.charset.StandardCharsets.UTF_8)))
                    .timeout(Duration.ofSeconds(15)).header("Accept", "application/json").GET().build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            TermiiDtos.Balance b = res.body() == null || res.body().isBlank() ? new TermiiDtos.Balance(null, null, null, null)
                    : json.readValue(res.body(), TermiiDtos.Balance.class);
            if (res.statusCode() >= 300 || b.balance() == null) {
                return IntegrationTestResult.failed("Termii refused the key (HTTP " + res.statusCode() + ")"
                        + (b.message() == null ? "" : ": " + b.message()));
            }
            String state = a.enabled() ? (StringUtils.hasText(a.senderId()) ? "on, sender " + a.senderId() : "on, but no sender ID")
                    : "switched off, so codes go through Infobip";
            return IntegrationTestResult.ok("Termii accepted the key: balance " + b.balance().toPlainString()
                    + (b.currency() == null ? "" : " " + b.currency()) + "; Termii is " + state);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return IntegrationTestResult.failed("Interrupted");
        } catch (Exception e) {
            return IntegrationTestResult.failed("Termii couldn't be reached at " + a.baseUrl() + ": " + e.getClass().getSimpleName());
        }
    }

    public InfobipClient.Sent sms(String to, String text) {
        Account a = account();
        if (!a.configured()) {
            return new InfobipClient.Sent(false, null, "Termii not configured");
        }
        TermiiDtos.SendRequest body = new TermiiDtos.SendRequest(a.apiKey(), to, a.senderId(), text, "plain", channel);
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(a.baseUrl() + "/api/sms/send")).timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/json").header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            TermiiDtos.SendResponse root = res.body() == null || res.body().isBlank() ? new TermiiDtos.SendResponse(null, null, null, null)
                    : json.readValue(res.body(), TermiiDtos.SendResponse.class);
            String id = root.messageIdStr() != null ? root.messageIdStr() : root.messageId() != null ? root.messageId() : "";
            if (res.statusCode() >= 300 || !StringUtils.hasText(id)) {
                String why = root.message() == null ? "" : root.message();
                return new InfobipClient.Sent(false, null, "Termii HTTP " + res.statusCode() + (why.isBlank() ? "" : ": " + why));
            }
            return new InfobipClient.Sent(true, id, null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new InfobipClient.Sent(false, null, "Interrupted");
        } catch (Exception e) {
            return new InfobipClient.Sent(false, null, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }
}
