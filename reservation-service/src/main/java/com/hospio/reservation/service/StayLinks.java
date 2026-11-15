package com.hospio.reservation.service;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * The guest's private link to their booking page, {@code https://hospio360.com/stay/<token>} (docs/sms-whatsapp.md). The token
 * is the booking id plus an HMAC of it under a server-only secret, so it can't be guessed or altered and nothing is stored:
 * 16 bytes of id and 12 of signature, base64url, 38 characters. Only booking ids that are UUIDs get a link.
 *
 * <p>The secret is {@code hospio.stay-links.secret} (env {@code STAY_LINK_SECRET}), defaulting to the internal API key, which is
 * already secret and per environment. Changing it turns every link already sent into "link not valid".
 */
@Component
public class StayLinks {

    private static final int SIG_BYTES = 12;
    private static final Base64.Encoder ENC = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DEC = Base64.getUrlDecoder();

    private final SecretKeySpec key;
    private final String baseUrl;

    public StayLinks(@Value("${hospio.stay-links.secret:${services.internal.api-key}}") String secret,
                     @Value("${hospio.stay-links.base-url:https://hospio360.com/stay/}") String baseUrl) {
        this.baseUrl = baseUrl == null || baseUrl.isBlank() ? "https://hospio360.com/stay/" : baseUrl.trim().replaceAll("/+$", "") + "/";
        if (!StringUtils.hasText(secret)) {
            throw new IllegalStateException("hospio.stay-links.secret (or services.internal.api-key) must be set");
        }
        this.key = new SecretKeySpec(("stay-link:" + secret).getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }

    /** The token for a booking, or empty when its id isn't a UUID. */
    public Optional<String> token(String bookingId) {
        byte[] id = idBytes(bookingId);
        if (id == null) {
            return Optional.empty();
        }
        byte[] out = Arrays.copyOf(id, id.length + SIG_BYTES);
        System.arraycopy(sign(id), 0, out, id.length, SIG_BYTES);
        return Optional.of(ENC.encodeToString(out));
    }

    /** The whole link, for the SMS and email (WhatsApp's button holds the address and takes only the token). */
    public Optional<String> url(String bookingId) {
        return token(bookingId).map(t -> baseUrl + t);
    }

    /** The booking id a token was made for, or empty when the token is malformed or its signature doesn't match. */
    public Optional<String> bookingId(String token) {
        if (token == null || token.length() != 38 || !token.matches("[A-Za-z0-9_-]+")) {
            return Optional.empty();
        }
        byte[] raw;
        try {
            raw = DEC.decode(token);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        if (raw.length != 16 + SIG_BYTES) {
            return Optional.empty();
        }
        byte[] id = Arrays.copyOf(raw, 16);
        byte[] given = Arrays.copyOfRange(raw, 16, raw.length);
        if (!MessageDigest.isEqual(given, Arrays.copyOf(sign(id), SIG_BYTES))) {
            return Optional.empty();
        }
        ByteBuffer b = ByteBuffer.wrap(id);
        return Optional.of(new UUID(b.getLong(), b.getLong()).toString());
    }

    private byte[] sign(byte[] id) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            return mac.doFinal(id);
        } catch (Exception e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }

    private static byte[] idBytes(String id) {
        try {
            UUID u = UUID.fromString(id);
            return ByteBuffer.allocate(16).putLong(u.getMostSignificantBits()).putLong(u.getLeastSignificantBits()).array();
        } catch (RuntimeException e) {
            return null;
        }
    }
}
