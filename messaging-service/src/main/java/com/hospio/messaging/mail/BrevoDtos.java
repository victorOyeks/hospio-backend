package com.hospio.messaging.mail;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/** Brevo's transactional email JSON ({@code POST /v3/smtp/email}, docs/email.md). */
public final class BrevoDtos {

    private BrevoDtos() {
    }

    /**
     * {@code {"sender":{…},"to":[{"email":…}],"replyTo":{…},"subject":…,"htmlContent":…,"textContent":…}}: sender, to and
     * subject always; replyTo, htmlContent and textContent only when there is one.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SendRequest(Sender sender, List<Address> to, Address replyTo, @JsonInclude(JsonInclude.Include.ALWAYS) String subject,
                              String htmlContent, String textContent) {
    }

    /** {@code email} always; {@code name} only when set. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Sender(@JsonInclude(JsonInclude.Include.ALWAYS) String email, String name) {
    }

    public record Address(String email) {
    }

    /** {@code {"messageId":"<…@smtp-relay.mailin.fr>"}} when accepted; {@code {"code":"…","message":"…"}} when not. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SendResponse(String messageId, String code, String message) {
    }

    /** {@code GET /v3/account}: the account's email and company (the admin portal's test); {@code code}/{@code message} when refused. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Account(String email, String companyName, String code, String message) {
    }
}
