package com.hospio.identity.service;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import com.hospio.common.enums.Role;
import com.hospio.common.exception.BadRequestException;
import com.hospio.common.exception.UnauthorizedException;
import com.hospio.common.tenancy.Tenant;
import com.hospio.common.tenancy.TenantContext;
import com.hospio.identity.dto.ProfileDtos;
import com.hospio.identity.entity.RecoveryCode;
import com.hospio.identity.entity.UserAccount;
import com.hospio.identity.repository.RecoveryCodeRepository;
import com.hospio.identity.repository.RoleGrantRepository;
import com.hospio.identity.repository.UserAccountRepository;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 2-step sign-in with an authenticator app (docs/profile.md): setup with a QR code, a first code to confirm, 10 one-time
 * recovery codes, turning it off with the password and a code. Wrong codes count on the sign-in counter
 * ({@code failed_sign_in_attempts}); the fifth locks sign-in for {@code sign-in-lock-minutes}. Everything here is the
 * caller's own account; the reads run as the platform because recovery codes are platform-only rows.
 */
@Service
@RequiredArgsConstructor
public class TwoStepService {

    static final String ISSUER = "Hospio360";
    static final int RECOVERY_CODES = 10;
    private static final String CODE_ALPHABET = "abcdefghjkmnpqrstuvwxyz23456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserAccountRepository users;
    private final RecoveryCodeRepository recoveryCodes;
    private final RoleGrantRepository grants;
    private final TwoStepSecrets secrets;
    private final PasswordEncoder passwordEncoder;

    @Value("${hospio.auth.sign-in-max-attempts:5}")
    int maxAttempts = 5;

    @Value("${hospio.auth.sign-in-lock-minutes:15}")
    long lockMinutes = 15;

    /** Roles that will have to use 2-step once this is enforced; read and reported, not enforced yet. */
    @Value("${hospio.auth.two-step.required-roles:}")
    String requiredRoles = "";

    @Transactional(readOnly = true)
    public ProfileDtos.TwoStepStatus status(String userId) {
        return system(() -> {
            UserAccount user = find(userId);
            return new ProfileDtos.TwoStepStatus(user.isTwoStepEnabled(), user.getTwoStepEnabledAt(),
                    user.isTwoStepEnabled() ? recoveryCodes.countByUserIdAndUsedAtIsNull(userId) : 0, required(user));
        });
    }

    /** Whether the person's roles are in {@code hospio.auth.two-step.required-roles}. Reported only; nothing is enforced. */
    boolean required(UserAccount user) {
        Set<String> roles = Arrays.stream(requiredRoles.split(",")).map(String::trim).filter(s -> !s.isEmpty())
                .map(s -> s.toUpperCase(Locale.ROOT)).collect(Collectors.toSet());
        if (roles.isEmpty()) {
            return false;
        }
        return grants.findByUserId(user.getId()).stream().map(g -> g.getRole()).map(Role::name).anyMatch(roles::contains);
    }

    @Transactional
    public ProfileDtos.TwoStepSetup setup(String userId) {
        return system(() -> {
            UserAccount user = find(userId);
            if (user.isTwoStepEnabled()) {
                throw new BadRequestException("2-step sign-in is already on. Turn it off first to set up a new app");
            }
            byte[] secret = Totp.newSecret();
            user.setTwoStepPendingSecret(secrets.seal(secret));
            users.save(user);
            String b32 = Totp.base32(secret);
            String label = URLEncoder.encode(ISSUER + ":" + user.getEmail(), StandardCharsets.UTF_8).replace("+", "%20");
            String uri = "otpauth://totp/" + label + "?secret=" + b32 + "&issuer=" + ISSUER + "&algorithm=SHA1&digits=6&period=30";
            return new ProfileDtos.TwoStepSetup(group(b32), uri, qrSvg(uri));
        });
    }

    /** Turns 2-step on with the first code from the app; the recovery codes come back once. A wrong code here isn't counted. */
    @Transactional
    public ProfileDtos.RecoveryCodes enable(String userId, String code) {
        return system(() -> {
            UserAccount user = find(userId);
            if (user.isTwoStepEnabled()) {
                throw new BadRequestException("2-step sign-in is already on");
            }
            if (user.getTwoStepPendingSecret() == null) {
                throw new BadRequestException("Start again: scan the QR code first");
            }
            byte[] secret = secrets.open(user.getTwoStepPendingSecret());
            OptionalLong step = Totp.matchingStep(secret, code, Instant.now().getEpochSecond(), null);
            if (step.isEmpty()) {
                throw new BadRequestException("That code is wrong. Type the 6 digits your app shows now (check the phone's clock is right)");
            }
            user.setTwoStepSecret(user.getTwoStepPendingSecret());
            user.setTwoStepPendingSecret(null);
            user.setTwoStepEnabled(true);
            user.setTwoStepEnabledAt(Instant.now());
            user.setTwoStepLastStep(step.getAsLong());
            users.save(user);
            return new ProfileDtos.RecoveryCodes(newRecoveryCodes(userId));
        });
    }

    /** Turns 2-step off: the password and an app or recovery code. Wrong ones count toward the sign-in lock. */
    @Transactional(noRollbackFor = BadRequestException.class)
    public ProfileDtos.TwoStepStatus disable(String userId, String password, String code) {
        system(() -> {
            UserAccount user = find(userId);
            if (!user.isTwoStepEnabled()) {
                throw new BadRequestException("2-step sign-in is already off");
            }
            assertNotLocked(user);
            if (user.getPasswordHash() == null || !passwordEncoder.matches(password, user.getPasswordHash())) {
                throw new BadRequestException(wrong(user, "Your password is wrong"));
            }
            if (!accept(user, code, true)) {
                throw new BadRequestException(wrong(user, "That code is wrong"));
            }
            user.setTwoStepEnabled(false);
            user.setTwoStepEnabledAt(null);
            user.setTwoStepSecret(null);
            user.setTwoStepPendingSecret(null);
            user.setTwoStepLastStep(null);
            user.setTwoStepChallengeHash(null);
            user.setTwoStepChallengeExpiresAt(null);
            clearFailures(user);
            users.save(user);
            recoveryCodes.deleteByUser(userId);
            return null;
        });
        return status(userId);
    }

    /** New recovery codes with a current code; the old ones stop working. */
    @Transactional(noRollbackFor = BadRequestException.class)
    public ProfileDtos.RecoveryCodes regenerate(String userId, String code) {
        return system(() -> {
            UserAccount user = find(userId);
            if (!user.isTwoStepEnabled()) {
                throw new BadRequestException("Turn 2-step sign-in on first");
            }
            assertNotLocked(user);
            if (!accept(user, code, true)) {
                throw new BadRequestException(wrong(user, "That code is wrong"));
            }
            clearFailures(user);
            users.save(user);
            return new ProfileDtos.RecoveryCodes(newRecoveryCodes(userId));
        });
    }

    /**
     * Checks an app code (6 digits, not used before) or, when {@code recoveryAllowed}, a recovery code (used up). Saves what
     * was used; counts nothing (callers decide). Runs inside the caller's transaction and tenant.
     */
    boolean accept(UserAccount user, String code, boolean recoveryAllowed) {
        if (code == null || code.isBlank() || user.getTwoStepSecret() == null) {
            return false;
        }
        String c = code.replaceAll("\\s", "");
        if (c.matches("\\d{6}")) {
            OptionalLong step = Totp.matchingStep(secrets.open(user.getTwoStepSecret()), c, Instant.now().getEpochSecond(),
                    user.getTwoStepLastStep());
            if (step.isPresent()) {
                user.setTwoStepLastStep(step.getAsLong());
                users.save(user);
                return true;
            }
            return false;
        }
        if (!recoveryAllowed) {
            return false;
        }
        return recoveryCodes.findByUserIdAndCodeHash(user.getId(), recoveryHash(user.getId(), c))
                .filter(rc -> rc.getUsedAt() == null)
                .map(rc -> {
                    rc.setUsedAt(Instant.now());
                    recoveryCodes.save(rc);
                    return true;
                }).orElse(false);
    }

    /** Counts a wrong password or code; the last one locks sign-in. The sentence to show. */
    String wrong(UserAccount user, String what) {
        Instant now = Instant.now();
        int failures = user.getFailedSignInAttempts() + 1;
        if (failures >= maxAttempts) {
            user.setFailedSignInAttempts(0);
            user.setSignInLockedUntil(now.plus(lockMinutes, ChronoUnit.MINUTES));
            users.save(user);
            return what + ". Too many wrong tries: sign-in is locked for " + lockMinutes + " minutes";
        }
        user.setFailedSignInAttempts(failures);
        users.save(user);
        int left = maxAttempts - failures;
        return left <= 2 ? what + ". " + left + (left == 1 ? " try" : " tries") + " left before sign-in is locked for " + lockMinutes + " minutes"
                : what;
    }

    void assertNotLocked(UserAccount user) {
        Instant now = Instant.now();
        if (user.getSignInLockedUntil() != null && user.getSignInLockedUntil().isAfter(now)) {
            long minutes = Math.max(1, (long) Math.ceil(Duration.between(now, user.getSignInLockedUntil()).getSeconds() / 60.0));
            throw new BadRequestException("Too many wrong tries. Try again in " + minutes + (minutes == 1 ? " minute" : " minutes"));
        }
    }

    static void clearFailures(UserAccount user) {
        user.setFailedSignInAttempts(0);
        user.setSignInLockedUntil(null);
    }

    private List<String> newRecoveryCodes(String userId) {
        recoveryCodes.deleteByUser(userId);
        recoveryCodes.flush();
        List<String> out = new ArrayList<>();
        for (int i = 0; i < RECOVERY_CODES; i++) {
            String code = randomCode();
            out.add(code);
            recoveryCodes.save(RecoveryCode.builder().userId(userId).codeHash(recoveryHash(userId, code)).build());
        }
        return out;
    }

    /** {@code xxxxx-xxxxx} from 31 unambiguous characters: about 49 bits. */
    static String randomCode() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 10; i++) {
            if (i == 5) {
                sb.append('-');
            }
            sb.append(CODE_ALPHABET.charAt(RANDOM.nextInt(CODE_ALPHABET.length())));
        }
        return sb.toString();
    }

    /** Case, spaces and dashes don't matter; salted with the person so equal codes of two people hash apart. */
    static String recoveryHash(String userId, String code) {
        String normalized = code.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        return Hashing.sha256(userId + ":" + normalized);
    }

    private static String group(String b32) {
        return b32.replaceAll("(.{4})(?!$)", "$1 ");
    }

    static String qrSvg(String text) {
        try {
            BitMatrix m = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0,
                    Map.of(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M, EncodeHintType.MARGIN, 2));
            StringBuilder path = new StringBuilder();
            for (int y = 0; y < m.getHeight(); y++) {
                for (int x = 0; x < m.getWidth(); x++) {
                    if (m.get(x, y)) {
                        path.append('M').append(x).append(' ').append(y).append("h1v1h-1z");
                    }
                }
            }
            return "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 " + m.getWidth() + " " + m.getHeight()
                    + "\" shape-rendering=\"crispEdges\"><rect width=\"100%\" height=\"100%\" fill=\"#fff\"/><path fill=\"#000\" d=\""
                    + path + "\"/></svg>";
        } catch (WriterException e) {
            throw new IllegalStateException(e);
        }
    }

    private UserAccount find(String userId) {
        return users.findById(userId).orElseThrow(() -> new UnauthorizedException("Account not found"));
    }

    private static <T> T system(java.util.function.Supplier<T> work) {
        return TenantContext.runWith(Tenant.system("two-step"), work::get);
    }
}
