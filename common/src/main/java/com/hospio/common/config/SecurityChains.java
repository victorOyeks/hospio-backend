package com.hospio.common.config;

import com.hospio.common.security.InternalOnlyPaths;
import com.hospio.common.security.PublicPaths;
import com.hospio.common.security.filter.InternalApiKeyFilter;
import com.hospio.common.security.filter.JwtFilter;
import java.util.List;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * The filter chain every service uses: stateless, JWT, internal-key gate on /internal/**, the
 * observability paths open, plus whatever public paths the service names (identity's sign-in, the
 * payment webhooks). Each service still owns its SecurityConfig; this only removes the repetition.
 */
public final class SecurityChains {

    private SecurityChains() {
    }

    public static InternalApiKeyFilter internalFilter(String internalApiKey) {
        return new InternalApiKeyFilter(List.of(InternalOnlyPaths.ALL_SERVICES), internalApiKey);
    }

    public static SecurityFilterChain standard(HttpSecurity http, JwtFilter jwtFilter, InternalApiKeyFilter internalFilter,
                                               AuthenticationEntryPoint entryPoint, AccessDeniedHandler deniedHandler,
                                               String... publicPaths) throws Exception {
        http.csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> {
                    if (publicPaths.length > 0) {
                        auth.requestMatchers(publicPaths).permitAll();
                    }
                    auth.requestMatchers(PublicPaths.OBSERVABILITY).permitAll()
                            .requestMatchers(PublicPaths.ERROR_DISPATCH).permitAll()
                            .anyRequest().authenticated();
                })
                .exceptionHandling(ex -> ex.authenticationEntryPoint(entryPoint).accessDeniedHandler(deniedHandler))
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(internalFilter, JwtFilter.class);
        return http.build();
    }
}
