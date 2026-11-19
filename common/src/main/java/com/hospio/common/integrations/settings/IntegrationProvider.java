package com.hospio.common.integrations.settings;

import static com.hospio.common.integrations.settings.FieldType.BOOLEAN;
import static com.hospio.common.integrations.settings.FieldType.NUMBER;
import static com.hospio.common.integrations.settings.FieldType.SECRET;
import static com.hospio.common.integrations.settings.FieldType.TEXT;
import static com.hospio.common.integrations.settings.FieldType.URL;
import static com.hospio.common.integrations.settings.IntegrationField.choice;
import static com.hospio.common.integrations.settings.IntegrationField.entry;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The third parties whose keys and addresses the admin portal manages (Settings · Integrations), each with its field catalogue: the
 * keys, labels, types and hints used to check what is typed, mask what is shown and build the screen. The fields are the components of
 * the provider's {@link IntegrationSettings} record, in the same order. {@code owner} is the service that uses the settings and runs
 * the provider's test.
 */
public enum IntegrationProvider {

    BREVO("Brevo (email API)", "EMAIL", Owner.MESSAGING, true, IntegrationSettings.Brevo.class, List.of(
            entry("apiKey", "API key", SECRET, "BREVO_API_KEY", "Brevo → SMTP & API → API keys. When set, email goes through Brevo's API instead of SMTP."),
            entry("fromAddress", "From address", TEXT, "MAIL_FROM_ADDRESS", "A verified Brevo sender, e.g. no-reply@hospio360.com."),
            entry("fromName", "Default From name", TEXT, "MAIL_FROM_NAME_DEFAULT", "Used when the location or business has no name of its own."),
            entry("webhookKey", "Webhook key", SECRET, "BREVO_WEBHOOK_KEY", "Any long random text. Brevo sends it with each email event "
                    + "(delivered, bounced) to https://<api>/messaging/webhooks/brevo as a Bearer token; without it every event is refused."))),

    SMTP("SMTP (email fallback)", "EMAIL", Owner.MESSAGING, true, IntegrationSettings.Smtp.class, List.of(
            entry("host", "Host", TEXT, "SMTP_HOST", "e.g. smtp-relay.brevo.com. Used only while there is no Brevo API key."),
            entry("port", "Port", NUMBER, "SMTP_PORT", "587 (STARTTLS)."),
            entry("username", "Username", TEXT, "SMTP_USERNAME", null),
            entry("password", "Password", SECRET, "SMTP_PASSWORD", null),
            entry("enabled", "Email on", BOOLEAN, "MAIL_ENABLED", "Off: no email goes out at all (Brevo API or SMTP); each one is recorded as not sent."))),

    INFOBIP("Infobip (SMS and WhatsApp)", "MESSAGING", Owner.MESSAGING, true, IntegrationSettings.Infobip.class, List.of(
            entry("baseUrl", "Base URL", URL, "INFOBIP_BASE_URL", "Your account's address, e.g. https://xxxxx.api.infobip.com."),
            entry("apiKey", "API key", SECRET, "INFOBIP_API_KEY", null),
            entry("whatsappSender", "WhatsApp sender", TEXT, "INFOBIP_WHATSAPP_SENDER", "The WhatsApp number messages come from, with its country code."),
            entry("smsSender", "SMS sender ID", TEXT, "INFOBIP_SMS_SENDER", "The approved alphanumeric sender; empty uses Infobip's default."),
            entry("notifyUrl", "Delivery report address", URL, "INFOBIP_NOTIFY_URL", "https://<api>/messaging/webhooks/infobip/reports. Reports need the webhook key too."),
            entry("webhookKey", "Webhook key", SECRET, "INFOBIP_WEBHOOK_KEY", "Any long random text; proves a delivery report comes from Infobip."),
            entry("smsEnabled", "SMS on", BOOLEAN, "SMS_ENABLED", "SMS is only used for sign-in codes (through Termii when Termii is on)."),
            entry("whatsappEnabled", "WhatsApp on", BOOLEAN, "WHATSAPP_ENABLED", null))),

    TERMII("Termii (SMS sign-in codes)", "MESSAGING", Owner.MESSAGING, true, IntegrationSettings.Termii.class, List.of(
            entry("enabled", "Termii on", BOOLEAN, "TERMII_ENABLED", "On: sign-in codes by SMS go through Termii instead of Infobip."),
            entry("baseUrl", "Base URL", URL, "TERMII_BASE_URL", "https://api.ng.termii.com"),
            entry("apiKey", "API key", SECRET, "TERMII_API_KEY", null),
            entry("senderId", "Sender ID", TEXT, "TERMII_SENDER_ID", "The approved sender ID, e.g. Hospio360."),
            entry("secretKey", "Secret key", SECRET, "TERMII_SECRET_KEY", "Checks Termii's delivery reports; without it every report is refused."))),

    PAYSTACK("Paystack (guest-message invoices)", "PAYMENTS", Owner.MESSAGING, true, IntegrationSettings.Paystack.class, List.of(
            entry("secretKey", "Secret key", SECRET, "PAYSTACK_SECRET_KEY", "sk_live_… (or sk_test_… on staging). Also checks Paystack's webhooks."))),

    MONIEPOINT("Moniepoint POS", "POS", Owner.PAYMENT, true, IntegrationSettings.Moniepoint.class, List.of(
            entry("baseUrl", "API address", URL, "MONIEPOINT_BASE_URL", "Moniepoint's POS API. Each location's own keys are set on the location."))),

    OPAY("OPay POS", "POS", Owner.PAYMENT, true, IntegrationSettings.Opay.class, List.of(
            entry("baseUrl", "API address", URL, "OPAY_BASE_URL", "https://payapi.opayweb.com/openApi. Each location's own keys are set on the location."))),

    FEE_DEBIT_OPAY("Daily fee debit: OPay", "FEE_DEBIT", Owner.PAYMENT, false, IntegrationSettings.FeeDebitOpay.class, List.of(
            entry("enabled", "Debit on", BOOLEAN, "FEE_DEBIT_OPAY_ENABLED", "On: the daily fee is paid out of the business's OPay account into Hospio360's."),
            entry("recipientAccount", "Hospio360 account", TEXT, "FEE_DEBIT_OPAY_RECIPIENT_ACCOUNT", "The OPay wallet or bank account the fee goes to."),
            entry("recipientName", "Account name", TEXT, "FEE_DEBIT_OPAY_RECIPIENT_NAME", null),
            entry("recipientBankCode", "Bank code", TEXT, "FEE_DEBIT_OPAY_RECIPIENT_BANK_CODE", "Needed only for BANK_TRANSFER."),
            choice("payoutType", "Payout type", "FEE_DEBIT_OPAY_PAYOUT_TYPE", "OPAY_WALLET or BANK_TRANSFER.", "OPAY_WALLET", "BANK_TRANSFER"))),

    FEE_DEBIT_MONIEPOINT("Daily fee debit: Moniepoint", "FEE_DEBIT", Owner.PAYMENT, false, IntegrationSettings.FeeDebitMoniepoint.class, List.of(
            entry("url", "Debit API address", URL, "FEE_DEBIT_MONIEPOINT_URL", "Moniepoint hasn't given a debit API yet; leave empty until it does.")));

    /** The service that uses these settings and runs the test. */
    public enum Owner {
        MESSAGING, PAYMENT
    }

    private final String label;
    private final String group;
    private final Owner owner;
    private final boolean testable;
    private final Class<? extends IntegrationSettings> type;
    private final List<IntegrationField> fields;

    IntegrationProvider(String label, String group, Owner owner, boolean testable, Class<? extends IntegrationSettings> type,
                        List<IntegrationField> fields) {
        this.label = label;
        this.group = group;
        this.owner = owner;
        this.testable = testable;
        this.type = type;
        this.fields = fields;
    }

    public String label() {
        return label;
    }

    /** EMAIL, MESSAGING, PAYMENTS, POS or FEE_DEBIT. */
    public String group() {
        return group;
    }

    public Owner owner() {
        return owner;
    }

    public boolean testable() {
        return testable;
    }

    public Class<? extends IntegrationSettings> type() {
        return type;
    }

    public List<IntegrationField> fields() {
        return fields;
    }

    public Optional<IntegrationField> field(String key) {
        return fields.stream().filter(f -> f.key().equals(key)).findFirst();
    }

    /** "brevo", "BREVO", "fee-debit-opay" → the provider; empty when there is none by that name. */
    public static Optional<IntegrationProvider> parse(String name) {
        if (name == null) {
            return Optional.empty();
        }
        String n = name.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        return Arrays.stream(values()).filter(p -> p.name().equals(n)).findFirst();
    }

    public static IntegrationProvider of(Class<? extends IntegrationSettings> type) {
        return Arrays.stream(values()).filter(p -> p.type == type).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("No provider for " + type.getSimpleName()));
    }
}
