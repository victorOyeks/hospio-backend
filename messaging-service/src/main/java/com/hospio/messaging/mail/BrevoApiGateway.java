package com.hospio.messaging.mail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Email through Brevo's transactional API (POST /v3/smtp/email) with the account's API key (docs/email.md). Used instead
 * of SMTP whenever {@code hospio.mail.brevo-api-key} (env BREVO_API_KEY) is set; the From address must be a verified
 * Brevo sender or on an authenticated domain. Brevo's message id is what gets recorded. 5xx and timeouts are worth one
 * more try; a 4xx (bad sender, bad address) is not.
 */
@Component
@Primary
@ConditionalOnExpression("'${hospio.mail.brevo-api-key:}' != ''")
public class BrevoApiGateway implements SmtpGateway {

    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final String apiKey;
    private final String baseUrl;

    public BrevoApiGateway(@Value("${hospio.mail.brevo-api-key:}") String apiKey,
                           @Value("${hospio.mail.brevo-base-url:https://api.brevo.com/v3}") String baseUrl) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.baseUrl = baseUrl.replaceAll("/+$", "");
    }

    @Override
    public boolean configured() {
        return StringUtils.hasText(apiKey);
    }

    @Override
    public Accepted send(Email email) {
        Map<String, Object> body = new HashMap<>();
        Map<String, Object> sender = new HashMap<>();
        sender.put("email", email.fromAddress());
        if (StringUtils.hasText(email.fromName())) {
            sender.put("name", email.fromName());
        }
        body.put("sender", sender);
        body.put("to", List.of(Map.of("email", email.to())));
        if (StringUtils.hasText(email.replyTo())) {
            body.put("replyTo", Map.of("email", email.replyTo()));
        }
        body.put("subject", email.subject());
        if (StringUtils.hasText(email.html())) {
            body.put("htmlContent", email.html());
        }
        if (StringUtils.hasText(email.text())) {
            body.put("textContent", email.text());
        }
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/smtp/email")).timeout(Duration.ofSeconds(20))
                    .header("api-key", apiKey).header("accept", "application/json").header("content-type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            JsonNode root = res.body() == null || res.body().isBlank() ? json.createObjectNode() : json.readTree(res.body());
            if (res.statusCode() >= 300) {
                String why = root.path("message").asText(root.path("code").asText("HTTP " + res.statusCode()));
                throw new SendFailure("Brevo " + res.statusCode() + ": " + why.replace(apiKey, "***"), res.statusCode() >= 500 || res.statusCode() == 429, null);
            }
            return new Accepted(root.path("messageId").asText(null), "Brevo API " + res.statusCode());
        } catch (SendFailure f) {
            throw f;
        } catch (HttpTimeoutException | ConnectException e) {
            throw new SendFailure("Brevo unreachable: " + e.getClass().getSimpleName(), true, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SendFailure("Interrupted", true, e);
        } catch (Exception e) {
            throw new SendFailure("Brevo: " + e.getClass().getSimpleName() + ": " + String.valueOf(e.getMessage()).replace(apiKey, "***"), false, e);
        }
    }
}
