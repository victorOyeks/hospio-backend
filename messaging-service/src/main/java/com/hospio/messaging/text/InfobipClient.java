package com.hospio.messaging.text;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Infobip, the SMS and WhatsApp provider (docs/sms-whatsapp.md). SMS goes through /sms/2/text/advanced, a WhatsApp through
 * /whatsapp/1/message/template from the Oyeks Global sender. Which templates Meta has approved is read from
 * /whatsapp/2/senders/{sender}/templates and kept for ten minutes, so a template starts being used on its own once approved.
 * Calls never throw: the answer says whether the provider took the message and, if not, why.
 */
@Slf4j
@Component
public class InfobipClient {

    /** The provider's answer: accepted (with its message id) or refused (with the reason). */
    public record Sent(boolean ok, String messageId, String error) {
        static Sent ok(String id) {
            return new Sent(true, id, null);
        }

        static Sent failed(String why) {
            return new Sent(false, null, why);
        }
    }

    /** A template's approval and category, as Meta reports it through Infobip. */
    public record TemplateInfo(String status, String category, String language) {
        public boolean approved() {
            return "APPROVED".equalsIgnoreCase(status);
        }
    }

    private static final Duration TEMPLATE_CACHE = Duration.ofMinutes(10);

    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final String baseUrl;
    private final String apiKey;
    private final String smsSender;
    private final String whatsAppSender;
    private final String notifyUrl;
    private volatile Map<String, TemplateInfo> templates = Map.of();
    private volatile Instant templatesReadAt = Instant.EPOCH;

    public InfobipClient(@Value("${hospio.infobip.base-url:}") String baseUrl,
                         @Value("${hospio.infobip.api-key:}") String apiKey,
                         @Value("${hospio.infobip.sms-sender:}") String smsSender,
                         @Value("${hospio.infobip.whatsapp-sender:}") String whatsAppSender,
                         @Value("${hospio.infobip.notify-url:}") String notifyUrl,
                         @Value("${hospio.infobip.webhook-key:}") String webhookKey) {
        this.baseUrl = baseUrl == null ? "" : baseUrl.trim().replaceAll("/+$", "");
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.smsSender = smsSender == null ? "" : smsSender.trim();
        this.whatsAppSender = whatsAppSender == null ? "" : whatsAppSender.trim().replace("+", "");
        // Where Infobip posts each message's final outcome (delivered, rejected and why); the key proves it is Infobip.
        this.notifyUrl = StringUtils.hasText(notifyUrl) && StringUtils.hasText(webhookKey)
                ? notifyUrl.trim() + (notifyUrl.contains("?") ? "&" : "?") + "key=" + java.net.URLEncoder.encode(webhookKey.trim(), java.nio.charset.StandardCharsets.UTF_8)
                : "";
    }

    /** Whether there is an account to send through at all (base URL and key set). */
    public boolean configured() {
        return StringUtils.hasText(baseUrl) && StringUtils.hasText(apiKey);
    }

    public boolean whatsAppConfigured() {
        return configured() && StringUtils.hasText(whatsAppSender);
    }

    public Sent sms(String to, String text) {
        if (!configured()) {
            return Sent.failed("SMS provider not configured");
        }
        Map<String, Object> message = new HashMap<>();
        message.put("destinations", List.of(Map.of("to", to)));
        message.put("text", text);
        if (StringUtils.hasText(smsSender)) {
            message.put("from", smsSender);
        }
        if (StringUtils.hasText(notifyUrl)) {
            message.put("notifyUrl", notifyUrl);
            message.put("notifyContentType", "application/json");
        }
        return send("/sms/2/text/advanced", Map.of("messages", List.of(message)));
    }

    /**
     * A WhatsApp template message. {@code params} fill the body's {{1}}, {{2}}…; an authentication (code) template also
     * carries the code as its copy-code button's parameter, as Infobip's guide does.
     */
    public Sent whatsAppTemplate(String to, String template, String language, List<String> params, boolean codeButton) {
        if (!whatsAppConfigured()) {
            return Sent.failed("WhatsApp sender not configured");
        }
        Map<String, Object> data = new HashMap<>();
        data.put("body", Map.of("placeholders", params == null ? List.of() : params));
        if (codeButton && params != null && !params.isEmpty()) {
            data.put("buttons", List.of(Map.of("type", "URL", "parameter", params.get(0))));
        }
        Map<String, Object> message = new HashMap<>(Map.of("from", whatsAppSender, "to", to, "messageId", UUID.randomUUID().toString(),
                "content", Map.of("templateName", template, "templateData", data, "language", language)));
        if (StringUtils.hasText(notifyUrl)) {
            message.put("notifyUrl", notifyUrl);
        }
        return send("/whatsapp/1/message/template", Map.of("messages", List.of(message)));
    }

    /** The sender's templates by name, refreshed at most every ten minutes; empty when they can't be read. */
    public Map<String, TemplateInfo> templates() {
        if (!whatsAppConfigured()) {
            return Map.of();
        }
        if (Instant.now().isBefore(templatesReadAt.plus(TEMPLATE_CACHE))) {
            return templates;
        }
        try {
            HttpRequest req = request("/whatsapp/2/senders/" + whatsAppSender + "/templates").GET().build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() >= 300) {
                log.warn("Infobip templates: HTTP {} {}", res.statusCode(), cut(res.body()));
                templatesReadAt = Instant.now().minus(TEMPLATE_CACHE).plusSeconds(60);
                return templates;
            }
            Map<String, TemplateInfo> read = new HashMap<>();
            for (JsonNode t : json.readTree(res.body()).path("templates")) {
                read.put(t.path("name").asText(), new TemplateInfo(t.path("status").asText(), t.path("category").asText(), t.path("language").asText()));
            }
            templates = Map.copyOf(read);
            templatesReadAt = Instant.now();
        } catch (Exception e) {
            log.warn("Infobip templates could not be read: {}", e.getMessage());
            templatesReadAt = Instant.now().minus(TEMPLATE_CACHE).plusSeconds(60);
        }
        return templates;
    }

    private Sent send(String path, Map<String, Object> body) {
        try {
            HttpRequest req = request(path).POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            JsonNode root = res.body() == null || res.body().isBlank() ? json.createObjectNode() : json.readTree(res.body());
            if (res.statusCode() >= 300) {
                String text = root.path("requestError").path("serviceException").path("text").asText("");
                return Sent.failed("HTTP " + res.statusCode() + (text.isBlank() ? "" : ": " + text));
            }
            JsonNode m = root.path("messages").path(0);
            String group = m.path("status").path("groupName").asText("");
            if ("REJECTED".equalsIgnoreCase(group) || "UNDELIVERABLE".equalsIgnoreCase(group)) {
                return Sent.failed(m.path("status").path("name").asText(group) + ": " + m.path("status").path("description").asText(""));
            }
            return Sent.ok(m.path("messageId").asText(null));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Sent.failed("Interrupted");
        } catch (Exception e) {
            return Sent.failed(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create(baseUrl + path)).timeout(Duration.ofSeconds(15))
                .header("Authorization", "App " + apiKey).header("Content-Type", "application/json").header("Accept", "application/json");
    }

    private static String cut(String s) {
        return s == null ? "" : s.length() > 300 ? s.substring(0, 300) : s;
    }

    /** Test seam: the templates as if read just now. */
    void useTemplates(Map<String, TemplateInfo> known) {
        templates = Map.copyOf(known);
        templatesReadAt = Instant.now();
    }

    /** The names of the approved templates (for the channels endpoint and logs). */
    public List<String> approvedTemplates() {
        List<String> out = new ArrayList<>();
        templates().forEach((name, t) -> {
            if (t.approved()) {
                out.add(name);
            }
        });
        return out;
    }
}
