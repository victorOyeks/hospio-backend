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
 * Phone confirmation with a 6-digit code. The code is never logged; it is handed to {@link DeliveryService}
 * as a PHONE_CODE SMS. On dev and staging {@link FixedOtp} ({@code hospio.otp.fixed-code}, 159753) is also accepted as a
 * backup code; it is never sent, the code sent is always random. Sign-in never depends on it; the sign-up wizard just shows the step.
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

    private FixedOtp fixedOtp = FixedOtp.OFF;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setFixedOtp(FixedOtp fixedOtp) {
        this.fixedOtp = fixedOtp;
    }

    @Value("${hospio.auth.phone-code-minutes:10}")
    private long codeMinutes;

    @Value("${hospio.auth.phone-code-max-attempts:5}")
    private int maxAttempts;

    @Value("${hospio.auth.phone-code-cooldown-seconds:60}")
    private long cooldownSeconds = 60;

    @Transactional
    public PhoneCodeResponse sendCode(String userId) {
        return sendCode(userId, false);
    }

    /** {@code whatsApp}: the code goes on WhatsApp ("Send on WhatsApp instead"), else by SMS. */
    @Transactional
    public PhoneCodeResponse sendCode(String userId, boolean whatsApp) {
        return sendCode(userId, whatsApp, false);
    }

    /**
     * Sends a code unless one went out less than the cooldown ago (then nothing is sent and the waiting code's expiry
     * and the seconds left come back with {@code sent=false}). {@code ifNone}: send only when no code is still valid,
     * which is what the "Check your phone" page asks when it opens, so reopening it never sends another.
     */
    @Transactional
    public PhoneCodeResponse sendCode(String userId, boolean whatsApp, boolean ifNone) {
        return TenantContext.runWith(Tenant.system("phone-code"), () -> {
            UserAccount user = find(userId);
            if (!StringUtils.hasText(user.getPhone())) {
                throw new BadRequestException("Add a phone number first");
            }
            if (ifNone && validExpiry(user, Instant.now()) != null) {
                return status(user);
            }
            return issue(user, whatsApp);
        });
    }

    /** Whether a code is waiting (its expiry, or null) and how long before another may be sent; sends nothing. */
    @Transactional(readOnly = true)
    public PhoneCodeResponse current(String userId) {
        return TenantContext.runWith(Tenant.system("phone-code"), () -> status(find(userId)));
    }

    /**
     * Stores a fresh code on an account that has a phone and sends it. Used by {@link #sendCode} and by sign-up,
     * so the verify screen already has a code waiting. Returns null when the account has no phone.
     */
    @Transactional
    public PhoneCodeResponse issue(UserAccount user) {
        return issue(user, false);
    }

    @Transactional
    public PhoneCodeResponse issue(UserAccount user, boolean whatsApp) {
        if (!StringUtils.hasText(user.getPhone())) {
            return null;
        }
        Instant now = Instant.now();
        long wait = retryIn(user, now);
        if (wait > 0) {
            log.info("Phone code for user {} not sent: the last one went out less than {}s ago", user.getId(), cooldownSeconds);
            return new PhoneCodeResponse(validExpiry(user, now), List.of(), false, wait);
        }
        // Always a random code, and only that code is ever sent: the fixed dev/staging code is accepted as a backup in verify(),
        // never put in a message.
        String code = String.format("%06d", RANDOM.nextInt(1_000_000));
        Instant expires = now.plus(codeMinutes, ChronoUnit.MINUTES);
        user.setPhoneCodeHash(Hashing.sha256(code));
        user.setPhoneCodeExpiresAt(expires);
        user.setPhoneCodeSentAt(now);
        user.setPhoneCodeAttempts(0);
        users.save(user);
        // Never the code itself (docs/sms-whatsapp.md): it is a credential, and logs are read by more people than the phone's owner.
        log.info("Phone verification code issued for user {} by {} (valid until {})", user.getId(), whatsApp ? "WhatsApp" : "SMS", expires);
        List<Delivery> sent = deliveryService.send(composer.forPhoneCode(user, code, expires, whatsApp));
        return new PhoneCodeResponse(expires, sent, true, cooldownSeconds);
    }

    private PhoneCodeResponse status(UserAccount user) {
        Instant now = Instant.now();
        return new PhoneCodeResponse(validExpiry(user, now), List.of(), false, retryIn(user, now));
    }

    /** The waiting code's expiry, or null when there is none or it has expired. */
    private static Instant validExpiry(UserAccount user, Instant now) {
        Instant expires = user.getPhoneCodeExpiresAt();
        return user.getPhoneCodeHash() != null && expires != null && expires.isAfter(now) ? expires : null;
    }

    /** Seconds left before another code may be sent (0 = now). */
    private long retryIn(UserAccount user, Instant now) {
        Instant sentAt = user.getPhoneCodeSentAt();
        if (sentAt == null) {
            return 0;
        }
        long left = cooldownSeconds - java.time.Duration.between(sentAt, now).getSeconds();
        return Math.max(0, Math.min(left, cooldownSeconds));
    }

    /** A wrong code is counted; after {@code maxAttempts} the code is dropped and a new one must be sent. */
    @Transactional(noRollbackFor = BadRequestException.class)
    public MeResponse verify(String userId, String code) {
        TenantContext.runWith(Tenant.system("phone-verify"), () -> {
            UserAccount user = find(userId);
            if (user.getPhoneCodeHash() == null || user.getPhoneCodeExpiresAt() == null) {
                throw new BadRequestException(user.getPhoneCodeAttempts() >= maxAttempts
                        ? "Too many wrong codes. Send a new code and try again"
                        : "Ask for a code first");
            }
            if (user.getPhoneCodeExpiresAt().isBefore(Instant.now())) {
                throw new BadRequestException("That code has expired. Send a new one");
            }
            if (!fixedOtp.matches(code) && !user.getPhoneCodeHash().equals(Hashing.sha256(code))) {
                int attempts = user.getPhoneCodeAttempts() + 1;
                user.setPhoneCodeAttempts(attempts);
                if (attempts >= maxAttempts) {
                    user.setPhoneCodeHash(null);
                    user.setPhoneCodeExpiresAt(null);
                    users.save(user);
                    throw new BadRequestException("Too many wrong codes. Send a new code and try again");
                }
                users.save(user);
                int left = maxAttempts - attempts;
                throw new BadRequestException("That code is wrong. " + left + (left == 1 ? " try" : " tries") + " left");
            }
            user.setPhoneVerified(true);
            user.setPhoneCodeAttempts(0);
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
