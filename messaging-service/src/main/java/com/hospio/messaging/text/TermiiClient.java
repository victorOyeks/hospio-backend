package com.hospio.messaging.text;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Termii, the SMS provider, used only for one-time codes (docs/sms-whatsapp.md); set up as CoRyd's notification-service does.
 * Codes go on Termii's {@code dnd} route from the approved sender ID ({@code TERMII_SENDER_ID}, Hospio360): "generic" is the
 * promotional route, which never reaches a number on Nigeria's DND list while Termii still says "sent". Switched on with
 * {@code TERMII_ENABLED}; the API key lives only in the environment. Calls never throw.
 */
@Slf4j
@Component
public class TermiiClient {

    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final String baseUrl;
    private final String apiKey;
    private final String senderId;
    private final String channel;
    private final boolean enabled;

    public TermiiClient(@Value("${hospio.termii.enabled:false}") boolean enabled,
                        @Value("${hospio.termii.base-url:https://api.ng.termii.com}") String baseUrl,
                        @Value("${hospio.termii.api-key:}") String apiKey,
                        @Value("${hospio.termii.sender-id:}") String senderId,
                        @Value("${hospio.termii.channel:dnd}") String channel) {
        this.enabled = enabled;
        this.baseUrl = baseUrl == null ? "" : baseUrl.trim().replaceAll("/+$", "");
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.senderId = senderId == null ? "" : senderId.trim();
        this.channel = StringUtils.hasText(channel) ? channel.trim() : "dnd";
    }

    /** Switched on, with a key and an approved sender ID: Termii refuses a send from an unregistered sender. */
    public boolean configured() {
        return enabled && StringUtils.hasText(baseUrl) && StringUtils.hasText(apiKey) && StringUtils.hasText(senderId);
    }

    public InfobipClient.Sent sms(String to, String text) {
        if (!configured()) {
            return new InfobipClient.Sent(false, null, "Termii not configured");
        }
        TermiiDtos.SendRequest body = new TermiiDtos.SendRequest(apiKey, to, senderId, text, "plain", channel);
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/api/sms/send")).timeout(Duration.ofSeconds(15))
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
