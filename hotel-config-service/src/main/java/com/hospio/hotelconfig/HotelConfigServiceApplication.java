package com.hospio.hotelconfig;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = {"com.hospio.hotelconfig", "com.hospio.common"})
@EnableJpaAuditing
@EnableScheduling
public class HotelConfigServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(HotelConfigServiceApplication.class, args);
    }
}
