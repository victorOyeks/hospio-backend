package com.hospio.housekeeping;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = {"com.hospio.housekeeping", "com.hospio.common"})
@EnableJpaAuditing
@EnableScheduling
public class HousekeepingServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(HousekeepingServiceApplication.class, args);
    }
}
