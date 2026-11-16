package com.hospio.platform.config;

import com.hospio.common.security.SecretBox;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The master key for the integration settings saved in the admin portal: SECRETS_KEY, else POS_SECRETS_KEY (the same key). */
@Configuration
public class SecretsConfig {

    @Bean
    public SecretBox secretBox(@Value("${hospio.secrets-key:}") String key) {
        return new SecretBox(key);
    }
}
