package com.hospio.common.integrations.settings;

/**
 * One provider's settings as saved in the admin portal (Settings · Integrations). Every component is nullable: null means "not saved",
 * and the consumer then uses its server environment value for that field ({@link IntegrationCatalogue#overlay}).
 */
public sealed interface IntegrationSettings permits IntegrationSettings.Brevo, IntegrationSettings.Smtp, IntegrationSettings.Infobip,
        IntegrationSettings.Termii, IntegrationSettings.Paystack, IntegrationSettings.Moniepoint, IntegrationSettings.Opay,
        IntegrationSettings.FeeDebitOpay, IntegrationSettings.FeeDebitMoniepoint {

    /** Email through Brevo's API (messaging-service). */
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    record Brevo(String apiKey, String fromAddress, String fromName) implements IntegrationSettings {
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

    /** Paystack, for paying guest-message invoices. */
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

    /** Hospio's daily fee taken from a hotel's OPay account into Hospio360's account. */
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    record FeeDebitOpay(Boolean enabled, String recipientAccount, String recipientName, String recipientBankCode, String payoutType)
            implements IntegrationSettings {
    }

    /** Moniepoint's debit API, once Moniepoint provides one. */
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    record FeeDebitMoniepoint(String url) implements IntegrationSettings {
    }
}
