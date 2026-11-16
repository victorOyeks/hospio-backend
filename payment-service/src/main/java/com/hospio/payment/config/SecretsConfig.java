package com.hospio.payment.config;

import com.hospio.common.integrations.settings.IntegrationSettingsReader;
import com.hospio.common.security.JwtUtil;
import com.hospio.common.security.SecretBox;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The master key for the hotels' POS keys (SECRETS_KEY, else POS_SECRETS_KEY: the same key and format as before, so keys saved
 * earlier still open), and the integration settings saved in the admin portal (Moniepoint and OPay addresses, the fee debit), read from
 * platform-service and cached a minute.
 */
@Configuration
public class SecretsConfig {

    @Bean
    public SecretBox secretBox(@Value("${hospio.secrets-key:}") String key) {
        return new SecretBox(key);
    }

    @Bean
    public IntegrationSettingsReader integrationSettingsReader(@Value("${services.platform.url}") String platformUrl, JwtUtil jwtUtil,
                                                               @Value("${services.internal.api-key}") String internalApiKey) {
        return new IntegrationSettingsReader(platformUrl, jwtUtil, internalApiKey);
    }
}
