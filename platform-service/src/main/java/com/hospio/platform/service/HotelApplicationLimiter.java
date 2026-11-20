package com.hospio.platform.service;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/** How many "Book a demo" requests one client address and one email may send in an hour (see {@link HourlyFormLimiter}). */
@Component
public class HotelApplicationLimiter extends HourlyFormLimiter {

    @Autowired
    public HotelApplicationLimiter(ObjectProvider<StringRedisTemplate> redis,
                                   @Value("${hospio.hotel-applications.per-address-per-hour:5}") int perAddress,
                                   @Value("${hospio.hotel-applications.per-email-per-hour:3}") int perEmail) {
        this(redis.getIfAvailable(), perAddress, perEmail);
    }

    HotelApplicationLimiter(StringRedisTemplate redis, int perAddress, int perEmail) {
        super(redis, "hotel-apply:", perAddress, perEmail);
    }
}
