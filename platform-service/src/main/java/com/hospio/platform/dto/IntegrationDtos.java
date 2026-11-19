package com.hospio.platform.dto;

import java.util.List;
import java.util.Map;

/** Settings · Integrations (docs/integrations.md): the provider cards, what a save sends, and a field as the screen shows it. */
public final class IntegrationDtos {

    private IntegrationDtos() {
    }

    /**
     * One provider's card. {@code group}: EMAIL, MESSAGING, PAYMENTS or POS. {@code updatedAt} is ISO-8601 (null while
     * nothing is saved).
     */
    public record IntegrationView(String provider, String label, String group, boolean testable, String updatedByName, String updatedAt,
                                  List<FieldView> fields) {
    }

    /**
     * One field. {@code type}: TEXT, SECRET, URL, NUMBER or BOOLEAN. {@code value} is what is saved (a SECRET masked, "••••••3456"),
     * null when nothing is saved for it and the server's environment applies.
     */
    public record FieldView(String key, String label, String type, String hint, String value, boolean saved) {
    }

    /**
     * A save: {@code fields} key → value (a blank or absent value keeps what is saved), {@code clear} the keys to remove so the
     * server's environment applies to them again.
     */
    public record IntegrationUpdate(Map<String, String> fields, List<String> clear) {
    }

    /**
     * One connection check as the service that owns it reports it (GET /internal/integrations/status on messaging-service and
     * payment-service): {@code key} whatsapp, email, sms or payment-provider; {@code status} CONNECTED, NOT_CONFIGURED, TEST_MODE or
     * ERROR; {@code detail} what was found, never a key's value.
     */
    public record ServiceCheck(String key, String status, String detail) {
    }
}
