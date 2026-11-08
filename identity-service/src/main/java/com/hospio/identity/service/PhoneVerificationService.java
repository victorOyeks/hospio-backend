package com.hospio.identity.service;

import com.hospio.common.delivery.Delivery;
import com.hospio.common.delivery.DeliveryService;
import com.hospio.common.exception.BadRequestException;
import com.hospio.common.exception.ResourceNotFoundException;
import com.hospio.common.tenancy.Tenant;
import com.hospio.common.tenancy.TenantContext;
import com.hospio.identity.dto.MeResponse;
import com.hospio.identity.dto.PhoneCodeResponse;
import com.hospio.identity.entity.UserAccount;
import com.hospio.identity.repository.UserAccountRepository;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Phone confirmation with a 6-digit code. The code is logged here and handed to {@link DeliveryService}
 * as a PHONE_CODE SMS (echoed in the response on staging, where {@code hospio.auth.otp-dev-code} 123456
 * is also always accepted). Sign-in never depends on it; the sign-up wizard just shows the step.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PhoneVerificationService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserAccountRepository users;
    private final UserService userService;
    private final DeliveryComposer composer;
    private final DeliveryService deliveryService;

    @Value("${hospio.auth.otp-dev-code:}")
    private String devCode;

    @Value("${hospio.auth.phone-code-minutes:10}")
    private long codeMinutes;

    @Transactional
    public PhoneCodeResponse sendCode(String userId) {
        return TenantContext.runWith(Tenant.system("phone-code"), () -> {
            UserAccount user = find(userId);
            if (!StringUtils.hasText(user.getPhone())) {
                throw new BadRequestException("Add a phone number first");
            }
            return issue(user);
        });
    }

    /**
     * Stores a fresh code on an account that has a phone and sends it. Used by {@link #sendCode} and by sign-up,
     * so the verify screen already has a code waiting. Returns null when the account has no phone.
     */
    @Transactional
    public PhoneCodeResponse issue(UserAccount user) {
        if (!StringUtils.hasText(user.getPhone())) {
            return null;
        }
        String code = String.format("%06d", RANDOM.nextInt(1_000_000));
        Instant expires = Instant.now().plus(codeMinutes, ChronoUnit.MINUTES);
        user.setPhoneCodeHash(Hashing.sha256(code));
        user.setPhoneCodeExpiresAt(expires);
        users.save(user);
        log.info("Phone verification code for {} ({}): {} (valid until {})", user.getEmail(), user.getPhone(), code, expires);
        List<Delivery> sent = deliveryService.send(composer.forPhoneCode(user, code, expires));
        return new PhoneCodeResponse(expires, sent);
    }

    @Transactional
    public MeResponse verify(String userId, String code) {
        TenantContext.runWith(Tenant.system("phone-verify"), () -> {
            UserAccount user = find(userId);
            boolean devMatch = StringUtils.hasText(devCode) && devCode.equals(code);
            if (!devMatch) {
                if (user.getPhoneCodeHash() == null || user.getPhoneCodeExpiresAt() == null) {
                    throw new BadRequestException("Ask for a code first");
                }
                if (user.getPhoneCodeExpiresAt().isBefore(Instant.now())) {
                    throw new BadRequestException("That code has expired. Ask for a new one");
                }
                if (!user.getPhoneCodeHash().equals(Hashing.sha256(code))) {
                    throw new BadRequestException("That code is wrong");
                }
            }
            user.setPhoneVerified(true);
            user.setPhoneCodeHash(null);
            user.setPhoneCodeExpiresAt(null);
            users.save(user);
        });
        return userService.me(userId);
    }

    private UserAccount find(String userId) {
        return users.findById(userId).orElseThrow(() -> new ResourceNotFoundException("Account not found"));
    }
}
