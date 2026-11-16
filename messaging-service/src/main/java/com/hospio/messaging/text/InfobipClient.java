package com.hospio.messaging.text;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hospio.messaging.text.InfobipDtos.Button;
import com.hospio.messaging.text.InfobipDtos.Destination;
import com.hospio.messaging.text.InfobipDtos.Header;
import com.hospio.messaging.text.InfobipDtos.SendResponse;
import com.hospio.messaging.text.InfobipDtos.SentMessage;
import com.hospio.messaging.text.InfobipDtos.SmsMessage;
import com.hospio.messaging.text.InfobipDtos.SmsRequest;
import com.hospio.messaging.text.InfobipDtos.Status;
import com.hospio.messaging.text.InfobipDtos.Template;
import com.hospio.messaging.text.InfobipDtos.TemplateBody;
import com.hospio.messaging.text.InfobipDtos.TemplateButton;
import com.hospio.messaging.text.InfobipDtos.TemplateContent;
import com.hospio.messaging.text.InfobipDtos.TemplateData;
import com.hospio.messaging.text.InfobipDtos.TemplateMessage;
import com.hospio.messaging.text.InfobipDtos.TemplateRequest;
import com.hospio.messaging.text.InfobipDtos.Templates;
import com.hospio.common.integrations.settings.IntegrationSettings;
import com.hospio.common.integrations.settings.IntegrationSettingsReader;
import com.hospio.common.integrations.settings.IntegrationTestResult;
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
import org.springframework.beans.factory.annotation.Autowired;
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

    /**
     * A template's approval and category, as Meta reports it through Infobip, and its shape: an image header (sent with the
     * Hospio360 banner) and a link button whose ending is a value (the template's last value, docs/sms-whatsapp.md).
     */
    public record TemplateInfo(String status, String category, String language, boolean imageHeader, boolean linkButton) {
        public TemplateInfo(String status, String category, String language) {
            this(status, category, language, false, false);
        }

        public boolean approved() {
            return "APPROVED".equalsIgnoreCase(status);
        }
    }

    private static final Duration TEMPLATE_CACHE = Duration.ofMinutes(10);

    /** The account as used for one call: base URL, key and senders normalised, the report address with its key. */
    private record Account(String baseUrl, String apiKey, String smsSender, String whatsAppSender, String notifyUrl) {
        boolean configured() {
            return StringUtils.hasText(baseUrl) && StringUtils.hasText(apiKey);
        }
    }

    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    /** The environment's values (INFOBIP_*); the admin portal's saved values are laid over them per call. */
    private final IntegrationSettings.Infobip env;
    private final String headerImageUrl;
    private IntegrationSettingsReader integrations;
    private volatile Map<String, TemplateInfo> templates = Map.of();
    private volatile Instant templatesReadAt = Instant.EPOCH;
    /** Which account and sender the templates were read for: a new sender or account in the portal reads them again. */
    private volatile String templatesFor = "";

    public InfobipClient(@Value("${hospio.infobip.base-url:}") String baseUrl,
                         @Value("${hospio.infobip.api-key:}") String apiKey,
                         @Value("${hospio.infobip.sms-sender:}") String smsSender,
                         @Value("${hospio.infobip.whatsapp-sender:}") String whatsAppSender,
                         @Value("${hospio.infobip.notify-url:}") String notifyUrl,
                         @Value("${hospio.infobip.webhook-key:}") String webhookKey,
                         @Value("${hospio.whatsapp.header-image-url:https://hospio360.com/whatsapp/hospio360-banner.png}") String headerImageUrl) {
        this.headerImageUrl = headerImageUrl == null ? "" : headerImageUrl.trim();
        this.env = new IntegrationSettings.Infobip(baseUrl, apiKey, whatsAppSender, smsSender, notifyUrl, webhookKey, null, null);
    }

    /** Settings · Integrations · Infobip in the admin portal (absent in tests: the environment's values only). */
    @Autowired(required = false)
    public void setIntegrations(IntegrationSettingsReader integrations) {
        this.integrations = integrations;
    }

    private Account account() {
        IntegrationSettings.Infobip in = integrations == null ? env : integrations.effective(env);
        String baseUrl = in.baseUrl() == null ? "" : in.baseUrl().trim().replaceAll("/+$", "");
        String notifyUrl = in.notifyUrl();
        String webhookKey = in.webhookKey();
        // Where Infobip posts each message's final outcome (delivered, rejected and why); the key proves it is Infobip.
        String notify = StringUtils.hasText(notifyUrl) && StringUtils.hasText(webhookKey)
                ? notifyUrl.trim() + (notifyUrl.contains("?") ? "&" : "?") + "key=" + java.net.URLEncoder.encode(webhookKey.trim(), java.nio.charset.StandardCharsets.UTF_8)
                : "";
        return new Account(baseUrl, in.apiKey() == null ? "" : in.apiKey().trim(), in.smsSender() == null ? "" : in.smsSender().trim(),
                in.whatsappSender() == null ? "" : in.whatsappSender().trim().replace("+", ""), notify);
    }

    /** Whether there is an account to send through at all (base URL and key set). */
    public boolean configured() {
        return account().configured();
    }

    public boolean whatsAppConfigured() {
        Account a = account();
        return a.configured() && StringUtils.hasText(a.whatsAppSender());
    }

    /**
     * The admin portal's test, with the settings in force: the WhatsApp sender's templates when there is a sender (GET
     * /whatsapp/2/senders/{sender}/templates), else the account balance (GET /account/1/balance). Sends nothing.
     */
    public IntegrationTestResult check() {
        Account a = account();
        if (!a.configured()) {
            return IntegrationTestResult.failed("Infobip needs a base URL and an API key, saved here or on the server");
        }
        try {
            boolean sender = StringUtils.hasText(a.whatsAppSender());
            String path = sender ? "/whatsapp/2/senders/" + a.whatsAppSender() + "/templates" : "/account/1/balance";
            HttpResponse<String> res = http.send(request(a, path).GET().build(), HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() >= 300) {
                SendResponse answer = res.body() == null || res.body().isBlank() ? new SendResponse(null, null)
                        : json.readValue(res.body(), SendResponse.class);
                String why = answer.requestError() == null || answer.requestError().serviceException() == null ? ""
                        : text(answer.requestError().serviceException().text());
                return IntegrationTestResult.failed("Infobip refused (HTTP " + res.statusCode() + ")" + (why.isBlank() ? "" : ": " + why));
            }
            if (sender) {
                Templates listed = json.readValue(res.body(), Templates.class);
                List<Template> all = listed.templates() == null ? List.of() : listed.templates();
                long approved = all.stream().filter(t -> t != null && "APPROVED".equalsIgnoreCase(t.status())).count();
                return IntegrationTestResult.ok("Infobip answered: WhatsApp sender " + a.whatsAppSender() + " has " + all.size()
                        + " templates, " + approved + " approved");
            }
            InfobipDtos.Balance b = json.readValue(res.body(), InfobipDtos.Balance.class);
            return IntegrationTestResult.ok("Infobip answered: balance " + (b.balance() == null ? "?" : b.balance().toPlainString())
                    + (b.currency() == null ? "" : " " + b.currency()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return IntegrationTestResult.failed("Interrupted");
        } catch (Exception e) {
            return IntegrationTestResult.failed("Infobip couldn't be reached at " + a.baseUrl() + ": " + e.getClass().getSimpleName());
        }
    }

    public Sent sms(String to, String text) {
        Account a = account();
        if (!a.configured()) {
            return Sent.failed("SMS provider not configured");
        }
        boolean notify = StringUtils.hasText(a.notifyUrl());
        SmsMessage message = new SmsMessage(List.of(new Destination(to)), text, StringUtils.hasText(a.smsSender()) ? a.smsSender() : null,
                notify ? a.notifyUrl() : null, notify ? "application/json" : null);
        return send(a, "/sms/2/text/advanced", new SmsRequest(List.of(message)));
    }

    /**
     * A WhatsApp template message. {@code params} fill the body's {{1}}, {{2}}…; an authentication (code) template also
     * carries the code as its copy-code button's parameter, as Infobip's guide does.
     */
    public Sent whatsAppTemplate(String to, String template, String language, List<String> params, boolean codeButton) {
        return whatsAppTemplate(to, template, language, params, new TemplateInfo("APPROVED", codeButton ? "AUTHENTICATION" : "UTILITY", language));
    }

    /**
     * A WhatsApp template message shaped as the template is: an image header gets the Hospio360 banner
     * ({@code hospio.whatsapp.header-image-url}); a link button takes the last value as its link's ending and the body the
     * rest; an authentication template's code also goes on its copy-code button.
     */
    public Sent whatsAppTemplate(String to, String template, String language, List<String> params, TemplateInfo shape) {
        return whatsAppTemplate(to, template, language, params, shape, null);
    }

    /** As above, with this message's own header image (the hotel's banner); null uses the Hospio360 banner. */
    public Sent whatsAppTemplate(String to, String template, String language, List<String> params, TemplateInfo shape, String headerImage) {
        Account a = account();
        if (!a.configured() || !StringUtils.hasText(a.whatsAppSender())) {
            return Sent.failed("WhatsApp sender not configured");
        }
        List<String> values = params == null ? List.of() : params;
        boolean link = shape.linkButton() && !values.isEmpty();
        TemplateBody body = new TemplateBody(link ? values.subList(0, values.size() - 1) : values);
        String image = StringUtils.hasText(headerImage) ? headerImage : headerImageUrl;
        Header header = shape.imageHeader() && StringUtils.hasText(image) ? new Header("IMAGE", image) : null;
        List<Button> buttons = null;
        if ("AUTHENTICATION".equalsIgnoreCase(shape.category()) && !values.isEmpty()) {
            buttons = List.of(new Button("URL", values.get(0)));
        } else if (link) {
            buttons = List.of(new Button("URL", values.get(values.size() - 1)));
        }
        TemplateMessage message = new TemplateMessage(a.whatsAppSender(), to, UUID.randomUUID().toString(),
                new TemplateContent(template, new TemplateData(body, header, buttons), language),
                StringUtils.hasText(a.notifyUrl()) ? a.notifyUrl() : null);
        return send(a, "/whatsapp/1/message/template", new TemplateRequest(List.of(message)));
    }

    /** The sender's templates by name, refreshed at most every ten minutes; empty when they can't be read. */
    public Map<String, TemplateInfo> templates() {
        Account a = account();
        if (!a.configured() || !StringUtils.hasText(a.whatsAppSender())) {
            return Map.of();
        }
        String readFor = a.baseUrl() + "|" + a.whatsAppSender() + "|" + Integer.toHexString(a.apiKey().hashCode());
        if (!readFor.equals(templatesFor)) {
            templates = Map.of();
            templatesReadAt = Instant.EPOCH;
            templatesFor = readFor;
        }
        if (Instant.now().isBefore(templatesReadAt.plus(TEMPLATE_CACHE))) {
            return templates;
        }
        try {
            HttpRequest req = request(a, "/whatsapp/2/senders/" + a.whatsAppSender() + "/templates").GET().build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() >= 300) {
                log.warn("Infobip templates: HTTP {} {}", res.statusCode(), cut(res.body()));
                templatesReadAt = Instant.now().minus(TEMPLATE_CACHE).plusSeconds(60);
                return templates;
            }
            Map<String, TemplateInfo> read = new HashMap<>();
            Templates listed = json.readValue(res.body(), Templates.class);
            for (Template t : listed.templates() == null ? List.<Template>of() : listed.templates()) {
                boolean image = t.structure() != null && t.structure().header() != null && "IMAGE".equalsIgnoreCase(t.structure().header().format());
                boolean link = false;
                if (t.structure() != null && t.structure().buttons() != null) {
                    for (TemplateButton b : t.structure().buttons()) {
                        link |= b != null && "URL".equalsIgnoreCase(b.type()) && b.url() != null && b.url().contains("{{");
                    }
                }
                read.put(text(t.name()), new TemplateInfo(text(t.status()), text(t.category()), text(t.language()), image, link));
            }
            templates = Map.copyOf(read);
            templatesReadAt = Instant.now();
        } catch (Exception e) {
            log.warn("Infobip templates could not be read: {}", e.getMessage());
            templatesReadAt = Instant.now().minus(TEMPLATE_CACHE).plusSeconds(60);
        }
        return templates;
    }

    private Sent send(Account a, String path, Object body) {
        try {
            HttpRequest req = request(a, path).POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            SendResponse answer = res.body() == null || res.body().isBlank() ? new SendResponse(null, null)
                    : json.readValue(res.body(), SendResponse.class);
            if (res.statusCode() >= 300) {
                String text = answer.requestError() == null || answer.requestError().serviceException() == null ? ""
                        : text(answer.requestError().serviceException().text());
                return Sent.failed("HTTP " + res.statusCode() + (text.isBlank() ? "" : ": " + text));
            }
            SentMessage m = answer.messages() == null || answer.messages().isEmpty() || answer.messages().get(0) == null
                    ? new SentMessage(null, null) : answer.messages().get(0);
            Status status = m.status() == null ? new Status(null, null, null) : m.status();
            String group = text(status.groupName());
            if ("REJECTED".equalsIgnoreCase(group) || "UNDELIVERABLE".equalsIgnoreCase(group)) {
                return Sent.failed((status.name() == null ? group : status.name()) + ": " + text(status.description()));
            }
            return Sent.ok(m.messageId());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Sent.failed("Interrupted");
        } catch (Exception e) {
            return Sent.failed(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /** A missing text as "" (what Infobip's answer had when a field was left out). */
    private static String text(String s) {
        return s == null ? "" : s;
    }

    private static HttpRequest.Builder request(Account a, String path) {
        return HttpRequest.newBuilder(URI.create(a.baseUrl() + path)).timeout(Duration.ofSeconds(15))
                .header("Authorization", "App " + a.apiKey()).header("Content-Type", "application/json").header("Accept", "application/json");
    }

    private static String cut(String s) {
        return s == null ? "" : s.length() > 300 ? s.substring(0, 300) : s;
    }

    /** Test seam: the templates as if read just now. */
    void useTemplates(Map<String, TemplateInfo> known) {
        Account a = account();
        templatesFor = a.baseUrl() + "|" + a.whatsAppSender() + "|" + Integer.toHexString(a.apiKey().hashCode());
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
