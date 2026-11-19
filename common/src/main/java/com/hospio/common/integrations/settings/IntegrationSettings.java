package com.hospio.common.integrations.settings;

/**
 * One provider's settings as saved in the admin portal (Settings · Integrations). Every component is nullable: null means "not saved",
 * and the consumer then uses its server environment value for that field ({@link IntegrationCatalogue#overlay}).
 */
public sealed interface IntegrationSettings permits IntegrationSettings.Brevo, IntegrationSettings.Smtp, IntegrationSettings.Infobip,
        IntegrationSettings.Termii, IntegrationSettings.Paystack, IntegrationSettings.Moniepoint, IntegrationSettings.Opay {

    /** Email through Brevo's API (messaging-service); {@code webhookKey} proves an email event (delivered, bounce) comes from Brevo. */
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    record Brevo(String apiKey, String fromAddress, String fromName, String webhookKey) implements IntegrationSettings {

        public Brevo(String apiKey, String fromAddress, String fromName) {
            this(apiKey, fromAddress, fromName, null);
        }
    }

    /** Email through an SMTP relay when there is no Brevo API key; {@code enabled} switches all email on or off. */
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    record Smtp(String host, Integer port, String username, String password, Boolean enabled) implements IntegrationSettings {
    }

    /** SMS and WhatsApp through Infobip, and the switches for both channels. */
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    record Infobip(String baseUrl, String apiKey, String whatsappSender, String smsSender, String notifyUrl, String webhookKey,
                   Boolean smsEnabled, Boolean whatsappEnabled) implements IntegrationSettings {
    }

    /** Sign-in codes by SMS through Termii (DND route). */
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    record Termii(Boolean enabled, String baseUrl, String apiKey, String senderId, String secretKey) implements IntegrationSettings {
    }

    /** Paystack, for owners paying the monthly Hospio360 invoice (and older device and guest-message invoices). */
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    record Paystack(String secretKey) implements IntegrationSettings {
    }

    /** Moniepoint's POS API address (each hotel's own keys stay with the hotel). */
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    record Moniepoint(String baseUrl) implements IntegrationSettings {
    }

    /** OPay's open API address (each hotel's own keys stay with the hotel). */
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    record Opay(String baseUrl) implements IntegrationSettings {
    }
}
