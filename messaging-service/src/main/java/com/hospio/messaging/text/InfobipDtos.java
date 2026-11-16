package com.hospio.messaging.text;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * Infobip's JSON, as its API documents it (docs/sms-whatsapp.md): what we send ({@code /sms/2/text/advanced},
 * {@code /whatsapp/1/message/template}), what it answers, the sender's templates ({@code /whatsapp/2/senders/{sender}/templates})
 * and the delivery reports it posts back. Optional parts are left out of a request when not set, as before.
 */
public final class InfobipDtos {

    private InfobipDtos() {
    }

    // ---- SMS: POST /sms/2/text/advanced -----------------------------------------------------------------------------

    /** {@code {"messages":[…]}} */
    public record SmsRequest(List<SmsMessage> messages) {
    }

    /** One SMS: {@code text} is always sent; {@code from}, {@code notifyUrl} and {@code notifyContentType} only when set. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SmsMessage(List<Destination> destinations, @JsonInclude(JsonInclude.Include.ALWAYS) String text, String from,
                             String notifyUrl, String notifyContentType) {
    }

    public record Destination(String to) {
    }

    // ---- WhatsApp: POST /whatsapp/1/message/template ----------------------------------------------------------------

    /** {@code {"messages":[…]}} */
    public record TemplateRequest(List<TemplateMessage> messages) {
    }

    /** One template message; {@code notifyUrl} only when delivery reports are set up. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TemplateMessage(String from, String to, String messageId, TemplateContent content, String notifyUrl) {
    }

    public record TemplateContent(String templateName, TemplateData templateData, String language) {
    }

    /** The body's placeholders always; the image header and the URL button only when the template has them. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TemplateData(TemplateBody body, Header header, List<Button> buttons) {
    }

    public record TemplateBody(List<String> placeholders) {
    }

    /** {@code {"type":"IMAGE","mediaUrl":"https://…"}} */
    public record Header(String type, String mediaUrl) {
    }

    /** {@code {"type":"URL","parameter":"…"}}: the dynamic ending of a link button (or an authentication template's code). */
    public record Button(String type, String parameter) {
    }

    // ---- Infobip's answer to a send ---------------------------------------------------------------------------------

    /** {@code {"messages":[{"messageId":…,"status":{…}}]}} on success, {@code {"requestError":{"serviceException":{"text":…}}}} on failure. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SendResponse(List<SentMessage> messages, RequestError requestError) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SentMessage(String messageId, Status status) {
    }

    /** A message's status (send answer and delivery report): {@code groupName} PENDING/DELIVERED/REJECTED/UNDELIVERABLE…. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Status(String groupName, String name, String description) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RequestError(ServiceException serviceException) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ServiceException(String messageId, String text) {
    }

    // ---- GET /whatsapp/2/senders/{sender}/templates -----------------------------------------------------------------

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Templates(List<Template> templates) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Template(String name, String status, String category, String language, Structure structure) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Structure(TemplateHeader header, List<TemplateButton> buttons) {
    }

    /** {@code format}: TEXT, IMAGE, DOCUMENT, VIDEO, LOCATION. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TemplateHeader(String format, String text) {
    }

    /** A URL button's {@code url} contains {@code {{1}}} when its ending is a value. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TemplateButton(String type, String text, String url) {
    }

    // ---- Delivery reports (POST /webhooks/infobip/reports) ----------------------------------------------------------

    /** One post can carry many reports. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Reports(List<Report> results) {
    }

    /** WhatsApp reports carry {@code channel} (and seen reports {@code seenAt}); SMS ones don't. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Report(String messageId, String to, String channel, String seenAt, Status status, ReportError error) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ReportError(String name, String description) {
    }

    // ---- GET /account/1/balance (the admin portal's test) ------------------------------------------------------------

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Balance(java.math.BigDecimal balance, String currency) {
    }
}
