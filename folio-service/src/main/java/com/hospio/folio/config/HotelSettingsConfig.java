package com.hospio.folio.config;

import com.hospio.common.security.JwtUtil;
import com.hospio.common.setup.HotelSettingsReader;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class HotelSettingsConfig {

    @Bean
    public HotelSettingsReader hotelSettingsReader(@Value("${services.hotelconfig.url}") String url, JwtUtil jwtUtil,
                                                   @Value("${services.internal.api-key}") String internalApiKey) {
        return new HotelSettingsReader(url, jwtUtil, internalApiKey);
    }
}
