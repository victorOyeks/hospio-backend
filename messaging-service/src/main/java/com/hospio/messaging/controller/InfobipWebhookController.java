package com.hospio.messaging.controller;

import com.hospio.messaging.mail.MailDispatcher;
import com.hospio.messaging.text.InfobipDtos.Report;
import com.hospio.messaging.text.InfobipDtos.ReportError;
import com.hospio.messaging.text.InfobipDtos.Reports;
import com.hospio.messaging.text.InfobipDtos.Status;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
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
 * message we send, with {@code key} (the webhook key saved in the admin portal, else INFOBIP_WEBHOOK_KEY) so only Infobip's
 * posts are believed. One post can carry many
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

    /** The admin portal's saved value wins over the environment's (absent in tests). */
    private com.hospio.common.integrations.settings.IntegrationSettingsReader integrations;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setIntegrations(com.hospio.common.integrations.settings.IntegrationSettingsReader integrations) {
        this.integrations = integrations;
    }

    private String webhookKey() {
        var env = new com.hospio.common.integrations.settings.IntegrationSettings.Infobip(null, null, null, null, null, webhookKey, null, null);
        String v = (integrations == null ? env : integrations.effective(env)).webhookKey();
        return v == null ? "" : v;
    }

    @PostMapping("/reports")
    public ResponseEntity<Void> reports(@RequestParam(required = false) String key, @RequestBody Reports body) {
        String webhookKey = webhookKey();
        if (!StringUtils.hasText(webhookKey) || key == null
                || !MessageDigest.isEqual(webhookKey.trim().getBytes(StandardCharsets.UTF_8), key.getBytes(StandardCharsets.UTF_8))) {
            return ResponseEntity.status(401).build();
        }
        List<Report> results = body == null || body.results() == null ? List.of() : body.results();
        int applied = 0;
        for (Report r : results) {
            String id = r == null ? null : r.messageId();
            if (id == null) {
                continue;
            }
            // WhatsApp reports carry a channel; SMS ones don't. Both use status.groupName and, when it failed, error.
            String channel = r.channel() == null ? "" : r.channel().toUpperCase();
            String prefix = channel.contains("WHATSAPP") || r.seenAt() != null ? "whatsapp:" : "sms:";
            Status status = r.status() == null ? new Status(null, null, null) : r.status();
            ReportError error = r.error() == null ? new ReportError(null, null) : r.error();
            String reason = reason(status, error);
            boolean done = dispatcher.applyReport(prefix + id, status.groupName(), reason)
                    || dispatcher.applyReport((prefix.equals("sms:") ? "whatsapp:" : "sms:") + id, status.groupName(), reason);
            applied += done ? 1 : 0;
        }
        log.info("Infobip reports: {} received, {} applied", results.size(), applied);
        return ResponseEntity.ok().build();
    }

    /** "Rejected - Destination on DND list (REJECTED_DND)", in the network's words. */
    private static String reason(Status status, ReportError error) {
        String desc = text(status.description());
        String name = text(status.name());
        String err = text(error.description());
        String errName = text(error.name());
        StringBuilder out = new StringBuilder(StringUtils.hasText(desc) ? desc : name);
        if (StringUtils.hasText(err) && !"No Error".equalsIgnoreCase(err) && !err.equalsIgnoreCase(desc)) {
            out.append(" - ").append(err);
        }
        if (StringUtils.hasText(name)) {
            out.append(" (").append(name).append(StringUtils.hasText(errName) && !"NO_ERROR".equals(errName) ? ", " + errName : "").append(")");
        }
        return out.toString().trim();
    }

    private static String text(String s) {
        return s == null ? "" : s;
    }
}
