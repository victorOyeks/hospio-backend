package com.hospio.folio;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = {"com.hospio.folio", "com.hospio.common"})
@EnableJpaAuditing
@EnableScheduling
public class FolioServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(FolioServiceApplication.class, args);
    }
}
