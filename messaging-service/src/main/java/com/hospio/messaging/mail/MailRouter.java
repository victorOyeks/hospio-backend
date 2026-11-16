package com.hospio.messaging.mail;

import com.hospio.common.integrations.settings.IntegrationSettings;
import com.hospio.common.integrations.settings.IntegrationSettingsReader;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * Which way an email goes, decided per email: Brevo's API when there is a Brevo key (saved in the admin portal, else BREVO_API_KEY),
 * else SMTP ({@link JavaMailGateway}). With nothing saved in the portal this is exactly the old choice made at start-up from
 * BREVO_API_KEY; with a key saved, the next email after the cached settings refresh (a minute at most) goes through Brevo.
 */
@Component
@Primary
public class MailRouter implements SmtpGateway {

    private final JavaMailGateway smtp;
    private final BrevoApiGateway brevo;
    private final String envKey;
    private final IntegrationSettingsReader integrations;

    public MailRouter(JavaMailGateway smtp, @Value("${hospio.mail.brevo-api-key:}") String envKey,
                      @Value("${hospio.mail.brevo-base-url:https://api.brevo.com/v3}") String baseUrl,
                      ObjectProvider<IntegrationSettingsReader> integrations) {
        this.smtp = smtp;
        this.envKey = envKey;
        this.integrations = integrations.getIfAvailable();
        this.brevo = new BrevoApiGateway(this::brevoKey, baseUrl);
    }

    /** The Brevo key in force: the portal's, else BREVO_API_KEY. */
    String brevoKey() {
        IntegrationSettings.Brevo env = new IntegrationSettings.Brevo(envKey, null, null);
        return (integrations == null ? env : integrations.effective(env)).apiKey();
    }

    public BrevoApiGateway brevo() {
        return brevo;
    }

    public JavaMailGateway smtp() {
        return smtp;
    }

    @Override
    public Accepted send(Email email) throws SendFailure {
        return brevo.configured() ? brevo.send(email) : smtp.send(email);
    }

    @Override
    public boolean configured() {
        return brevo.configured() || smtp.configured();
    }
}
