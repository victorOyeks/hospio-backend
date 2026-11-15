package com.hospio.messaging.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hospio.messaging.mail.MailDispatcher;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
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
 * Termii's delivery reports (docs/sms-whatsapp.md), set in the Termii console as
 * {@code https://<api>/messaging/webhooks/termii/reports}. Believed only when {@code X-Termii-Signature} is the HMAC-SHA512 of
 * the body under the account's secret key ({@code TERMII_SECRET_KEY}); without the secret set, every post is refused.
 * "Delivered" makes the record DELIVERED; "DND Active", "Message Failed", "Rejected" and "Expired" make it FAILED with those words.
 */
@Slf4j
@RestController
@RequestMapping("/webhooks/termii")
@RequiredArgsConstructor
public class TermiiWebhookController {

    private final MailDispatcher dispatcher;
    private final ObjectMapper json = new ObjectMapper();

    @Value("${hospio.termii.secret-key:}")
    private String secretKey;

    @PostMapping("/reports")
    public ResponseEntity<Void> report(@RequestHeader(value = "X-Termii-Signature", required = false) String signature,
                                       @RequestBody String raw) {
        if (!StringUtils.hasText(secretKey) || !valid(raw, signature)) {
            return ResponseEntity.status(401).build();
        }
        try {
            JsonNode r = json.readTree(raw);
            String id = r.path("message_id").asText(r.path("message_id_str").asText(r.path("id").asText(null)));
            String status = r.path("status").asText("");
            String group = group(status);
            boolean applied = id != null && group != null && dispatcher.applyReport("termii:" + id, group, status);
            log.info("Termii report {}: {} ({})", id, status, applied ? "applied" : "not applied");
        } catch (Exception e) {
            log.warn("Termii report could not be read: {}", e.getMessage());
        }
        return ResponseEntity.ok().build();
    }

    /** Termii's words to the status groups MailDispatcher knows; anything still on its way is null (changes nothing). */
    static String group(String status) {
        String s = status == null ? "" : status.toLowerCase(Locale.ROOT);
        if (s.startsWith("delivered")) {
            return "DELIVERED";
        }
        if (s.contains("dnd") || s.contains("rejected")) {
            return "REJECTED";
        }
        if (s.contains("expired")) {
            return "EXPIRED";
        }
        if (s.contains("failed")) {
            return "UNDELIVERABLE";
        }
        return null;
    }

    boolean valid(String raw, String signature) {
        if (signature == null || raw == null) {
            return false;
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA512");
            mac.init(new SecretKeySpec(secretKey.trim().getBytes(StandardCharsets.UTF_8), "HmacSHA512"));
            byte[] expected = HexFormat.of().formatHex(mac.doFinal(raw.getBytes(StandardCharsets.UTF_8))).getBytes(StandardCharsets.UTF_8);
            return MessageDigest.isEqual(expected, signature.trim().toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            return false;
        }
    }
}
