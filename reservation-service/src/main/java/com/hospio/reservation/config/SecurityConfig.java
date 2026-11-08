package com.hospio.reservation.config;

import com.hospio.common.config.SecurityChains;
import com.hospio.common.security.filter.InternalApiKeyFilter;
import com.hospio.common.security.filter.JwtFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;

@Configuration
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtFilter jwtFilter;
    private final AuthenticationEntryPoint authenticationEntryPoint;
    private final AccessDeniedHandler accessDeniedHandler;

    @Value("${services.internal.api-key}")
    private String internalApiKey;

    @Bean
    public InternalApiKeyFilter internalApiKeyFilter() {
        return SecurityChains.internalFilter(internalApiKey);
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, InternalApiKeyFilter internalApiKeyFilter) throws Exception {
        return SecurityChains.standard(http, jwtFilter, internalApiKeyFilter, authenticationEntryPoint, accessDeniedHandler);
    }
}
