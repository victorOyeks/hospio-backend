package com.hospio.property;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = {"com.hospio.property", "com.hospio.common"})
@EnableJpaAuditing
@EnableScheduling
public class PropertyServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(PropertyServiceApplication.class, args);
    }
}
