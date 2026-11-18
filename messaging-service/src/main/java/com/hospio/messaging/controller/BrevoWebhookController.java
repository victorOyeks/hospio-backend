package com.hospio.messaging.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hospio.messaging.mail.MailDispatcher;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Brevo's transactional email events (docs/email.md, "Delivery tracking"), set in Brevo as
 * {@code https://<api>/messaging/webhooks/brevo} with the webhook key as a Bearer token ({@code Authorization: Bearer <key>}),
 * or as Basic authentication whose password is the key (Brevo's {@code https://hospio:<key>@<api>/…} form). The key is the one
 * saved in the admin portal (Settings · Integrations · Brevo → Webhook key), else {@code BREVO_WEBHOOK_KEY}; without one every
 * post is refused. It is never taken from the URL's query (nginx would log it) and never logged.
 *
 * <p>One event ({@code {"event":"delivered","message-id":"<…>","reason":…,"ts_event":…}}) or a batch (an array) per post; each
 * is applied with {@link MailDispatcher#applyEmailEvent}. Unknown events and message ids are ignored with 200 so Brevo doesn't
 * retry them.
 */
@Slf4j
@RestController
@RequestMapping("/webhooks/brevo")
@RequiredArgsConstructor
public class BrevoWebhookController {

    private final MailDispatcher dispatcher;
    private final ObjectMapper json = new ObjectMapper();

    @Value("${hospio.brevo.webhook-key:}")
    private String webhookKey;

    /** The admin portal's saved value wins over the environment's (absent in tests). */
    private com.hospio.common.integrations.settings.IntegrationSettingsReader integrations;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setIntegrations(com.hospio.common.integrations.settings.IntegrationSettingsReader integrations) {
        this.integrations = integrations;
    }

    private String webhookKey() {
        var env = new com.hospio.common.integrations.settings.IntegrationSettings.Brevo(null, null, null, webhookKey);
        String v = (integrations == null ? env : integrations.effective(env)).webhookKey();
        return v == null ? "" : v.trim();
    }

    @PostMapping
    public ResponseEntity<Void> events(@RequestHeader(value = "Authorization", required = false) String authorization,
                                       @RequestBody(required = false) String raw) {
        String key = webhookKey();
        if (key.isEmpty() || !authorized(authorization, key)) {
            return ResponseEntity.status(401).build();
        }
        List<JsonNode> events = new ArrayList<>();
        try {
            JsonNode root = raw == null || raw.isBlank() ? null : json.readTree(raw);
            if (root != null && root.isArray()) {
                root.forEach(events::add);
            } else if (root != null && root.isObject()) {
                events.add(root);
            }
        } catch (Exception e) {
            log.warn("Brevo event could not be read: {}", e.getClass().getSimpleName());
            return ResponseEntity.ok().build();
        }
        int applied = 0;
        for (JsonNode e : events) {
            String event = text(e, "event");
            String messageId = text(e, "message-id");
            if (messageId == null) {
                messageId = text(e, "messageId");
            }
            String reason = text(e, "reason");
            applied += dispatcher.applyEmailEvent(messageId, event, reason, when(e)) ? 1 : 0;
        }
        log.info("Brevo events: {} received, {} applied", events.size(), applied);
        return ResponseEntity.ok().build();
    }

    /** {@code Bearer <key>}, or Basic with the key as the password (any user name); compared in constant time. */
    static boolean authorized(String authorization, String key) {
        if (!StringUtils.hasText(authorization) || !StringUtils.hasText(key)) {
            return false;
        }
        String a = authorization.trim();
        String given = null;
        if (a.regionMatches(true, 0, "Bearer ", 0, 7)) {
            given = a.substring(7).trim();
        } else if (a.regionMatches(true, 0, "Basic ", 0, 6)) {
            try {
                String pair = new String(Base64.getDecoder().decode(a.substring(6).trim()), StandardCharsets.UTF_8);
                int colon = pair.indexOf(':');
                given = colon < 0 ? null : pair.substring(colon + 1);
            } catch (IllegalArgumentException e) {
                return false;
            }
        }
        return given != null && MessageDigest.isEqual(key.getBytes(StandardCharsets.UTF_8), given.getBytes(StandardCharsets.UTF_8));
    }

    /** When the event happened: ts_event (seconds), ts_epoch (milliseconds) or ts; now when none is given. */
    private static Instant when(JsonNode e) {
        if (e.hasNonNull("ts_epoch") && e.get("ts_epoch").canConvertToLong()) {
            return Instant.ofEpochMilli(e.get("ts_epoch").asLong());
        }
        for (String f : List.of("ts_event", "ts")) {
            if (e.hasNonNull(f) && e.get(f).canConvertToLong()) {
                return Instant.ofEpochSecond(e.get(f).asLong());
            }
        }
        return Instant.now();
    }

    private static String text(JsonNode e, String field) {
        JsonNode v = e.get(field);
        return v == null || v.isNull() ? null : StringUtils.hasText(v.asText()) ? v.asText().trim() : null;
    }
}
