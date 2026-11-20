package com.hospio.platform.service;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/** How many partner applications one client address and one email may send in an hour (see {@link HourlyFormLimiter}). */
@Component
public class PartnerApplicationLimiter extends HourlyFormLimiter {

    @Autowired
    public PartnerApplicationLimiter(ObjectProvider<StringRedisTemplate> redis,
                                     @Value("${hospio.partner-applications.per-address-per-hour:3}") int perAddress,
                                     @Value("${hospio.partner-applications.per-email-per-hour:3}") int perEmail) {
        this(redis.getIfAvailable(), perAddress, perEmail);
    }

    PartnerApplicationLimiter(StringRedisTemplate redis, int perAddress, int perEmail) {
        super(redis, "partner-apply:", perAddress, perEmail);
    }
}
