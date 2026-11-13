package com.hospio.messaging.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.hospio.messaging.mail.MailDispatcher;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Infobip posts each SMS's and WhatsApp's delivery report here (docs/sms-whatsapp.md): the address is given on every
 * message we send, with {@code key} (INFOBIP_WEBHOOK_KEY) so only Infobip's posts are believed. One post can carry many
 * reports ({@code results[]}); each moves its delivery record to DELIVERED or FAILED with the reason.
 */
@Slf4j
@RestController
@RequestMapping("/webhooks/infobip")
@RequiredArgsConstructor
public class InfobipWebhookController {

    private final MailDispatcher dispatcher;

    @Value("${hospio.infobip.webhook-key:}")
    private String webhookKey;

    @PostMapping("/reports")
    public ResponseEntity<Void> reports(@RequestParam(required = false) String key, @RequestBody JsonNode body) {
        if (!StringUtils.hasText(webhookKey) || key == null
                || !MessageDigest.isEqual(webhookKey.trim().getBytes(StandardCharsets.UTF_8), key.getBytes(StandardCharsets.UTF_8))) {
            return ResponseEntity.status(401).build();
        }
        int applied = 0;
        for (JsonNode r : body.path("results")) {
            String id = r.path("messageId").asText(null);
            if (id == null) {
                continue;
            }
            // WhatsApp reports carry a channel; SMS ones don't. Both use status.groupName and, when it failed, error.
            String channel = r.path("channel").asText("").toUpperCase();
            String prefix = channel.contains("WHATSAPP") || r.has("seenAt") ? "whatsapp:" : "sms:";
            JsonNode status = r.path("status");
            JsonNode error = r.path("error");
            String reason = reason(status, error);
            boolean done = dispatcher.applyReport(prefix + id, status.path("groupName").asText(null), reason)
                    || dispatcher.applyReport((prefix.equals("sms:") ? "whatsapp:" : "sms:") + id, status.path("groupName").asText(null), reason);
            applied += done ? 1 : 0;
        }
        log.info("Infobip reports: {} received, {} applied", body.path("results").size(), applied);
        return ResponseEntity.ok().build();
    }

    /** "Rejected - Destination on DND list (REJECTED_DND)", in the network's words. */
    private static String reason(JsonNode status, JsonNode error) {
        String desc = status.path("description").asText("");
        String name = status.path("name").asText("");
        String err = error.path("description").asText("");
        String errName = error.path("name").asText("");
        StringBuilder out = new StringBuilder(StringUtils.hasText(desc) ? desc : name);
        if (StringUtils.hasText(err) && !"No Error".equalsIgnoreCase(err) && !err.equalsIgnoreCase(desc)) {
            out.append(" - ").append(err);
        }
        if (StringUtils.hasText(name)) {
            out.append(" (").append(name).append(StringUtils.hasText(errName) && !"NO_ERROR".equals(errName) ? ", " + errName : "").append(")");
        }
        return out.toString().trim();
    }
}
