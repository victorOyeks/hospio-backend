package com.hospio.identity.config;

import com.hospio.identity.service.SelfSignUpSwitch;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * While self sign-up is off, POST /auth/sign-up answers 410 with the "book a demo" sentence before its body is even read, so an
 * empty or partial form gets the same clear answer as a full one (SignUpService checks again, for any other caller).
 */
@Configuration
@RequiredArgsConstructor
public class SelfSignUpConfig implements WebMvcConfigurer {

    private final SelfSignUpSwitch selfSignUp;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override
            public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
                selfSignUp.require();
                return true;
            }
        }).addPathPatterns("/auth/sign-up", "/auth/sign-up/**");
    }
}
