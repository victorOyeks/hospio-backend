package com.hospio.messaging.config;

import com.hospio.common.integrations.settings.IntegrationSettingsReader;
import com.hospio.common.security.JwtUtil;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The integration settings saved in the admin portal (Brevo, SMTP, Infobip, Termii, Paystack), read from platform-service and cached a
 * minute. Each client lays them over its environment values per use, so a saved change is in force within a minute, no restart.
 */
@Configuration
public class IntegrationsConfig {

    @Bean
    public IntegrationSettingsReader integrationSettingsReader(@Value("${services.platform.url}") String platformUrl, JwtUtil jwtUtil,
                                                               @Value("${services.internal.api-key}") String internalApiKey) {
        return new IntegrationSettingsReader(platformUrl, jwtUtil, internalApiKey);
    }
}
