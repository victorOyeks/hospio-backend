package com.hospio.common.config;

import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Every service scans com.hospio.common, so this registers the bearer-JWT scheme everywhere at once. */
@Configuration
@SecurityScheme(
        name = "bearerAuth",
        type = SecuritySchemeType.HTTP,
        scheme = "bearer",
        bearerFormat = "JWT",
        description = "Paste the access token returned by identity-service's /identity/auth/sign-in"
)
public class OpenApiConfig {

    @Bean
    public OpenAPI openApi() {
        return new OpenAPI().addSecurityItem(new SecurityRequirement().addList("bearerAuth"));
    }
}
