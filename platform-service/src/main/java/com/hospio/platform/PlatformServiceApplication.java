package com.hospio.platform;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = {"com.hospio.platform", "com.hospio.common"})
@EnableJpaAuditing
@EnableScheduling
public class PlatformServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(PlatformServiceApplication.class, args);
    }
}
