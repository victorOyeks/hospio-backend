package com.hospio.identity.service;

import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.OptionalLong;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Time-based one-time passwords (RFC 6238 over RFC 4226 HOTP) as authenticator apps make them: HMAC-SHA1, 30-second
 * steps from the Unix epoch, 6 digits. {@link #matchingStep} allows one step of clock drift either way and never a step at
 * or before the last one used, so a code works once. Tested against the RFC 6238 appendix B vectors.
 */
public final class Totp {

    public static final int STEP_SECONDS = 30;
    public static final int DIGITS = 6;
    private static final int SECRET_BYTES = 20;
    private static final SecureRandom RANDOM = new SecureRandom();

    private Totp() {
    }

    /** 160 random bits, the size RFC 4226 recommends for HMAC-SHA1. */
    public static byte[] newSecret() {
        byte[] secret = new byte[SECRET_BYTES];
        RANDOM.nextBytes(secret);
        return secret;
    }

    public static long step(long epochSeconds) {
        return Math.floorDiv(epochSeconds, STEP_SECONDS);
    }

    /** HOTP (RFC 4226 section 5.3) for one counter value, with the given hash ("HmacSHA1", "HmacSHA256", "HmacSHA512"). */
    static String code(byte[] key, long counter, int digits, String algorithm) {
        try {
            Mac mac = Mac.getInstance(algorithm);
            mac.init(new SecretKeySpec(key, algorithm));
            byte[] hash = mac.doFinal(ByteBuffer.allocate(8).putLong(counter).array());
            int offset = hash[hash.length - 1] & 0x0f;
            int binary = ((hash[offset] & 0x7f) << 24) | ((hash[offset + 1] & 0xff) << 16) | ((hash[offset + 2] & 0xff) << 8)
                    | (hash[offset + 3] & 0xff);
            long otp = binary % (long) Math.pow(10, digits);
            return String.format("%0" + digits + "d", otp);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The app's code for a time. */
    public static String codeAt(byte[] key, long epochSeconds) {
        return code(key, step(epochSeconds), DIGITS, "HmacSHA1");
    }

    /**
     * The step the code belongs to (now, one before or one after), if any and only if it is later than {@code lastUsedStep}.
     * Compares in constant time.
     */
    public static OptionalLong matchingStep(byte[] key, String code, long epochSeconds, Long lastUsedStep) {
        if (code == null) {
            return OptionalLong.empty();
        }
        String c = code.replaceAll("\\s", "");
        if (!c.matches("\\d{" + DIGITS + "}")) {
            return OptionalLong.empty();
        }
        long now = step(epochSeconds);
        for (long s = now - 1; s <= now + 1; s++) {
            if (lastUsedStep != null && s <= lastUsedStep) {
                continue;
            }
            if (MessageDigest.isEqual(code(key, s, DIGITS, "HmacSHA1").getBytes(), c.getBytes())) {
                return OptionalLong.of(s);
            }
        }
        return OptionalLong.empty();
    }

    /** RFC 4648 base32 without padding, as otpauth:// URIs carry the secret. */
    public static String base32(byte[] data) {
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
        StringBuilder out = new StringBuilder();
        int buffer = 0;
        int bits = 0;
        for (byte b : data) {
            buffer = (buffer << 8) | (b & 0xff);
            bits += 8;
            while (bits >= 5) {
                out.append(alphabet.charAt((buffer >> (bits - 5)) & 31));
                bits -= 5;
            }
        }
        if (bits > 0) {
            out.append(alphabet.charAt((buffer << (5 - bits)) & 31));
        }
        return out.toString();
    }

    public static byte[] fromBase32(String text) {
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
        String t = text.replaceAll("[\\s=-]", "").toUpperCase();
        ByteBuffer out = ByteBuffer.allocate(t.length() * 5 / 8);
        int buffer = 0;
        int bits = 0;
        for (char ch : t.toCharArray()) {
            int v = alphabet.indexOf(ch);
            if (v < 0) {
                throw new IllegalArgumentException("Not base32");
            }
            buffer = (buffer << 5) | v;
            bits += 5;
            if (bits >= 8) {
                out.put((byte) ((buffer >> (bits - 8)) & 0xff));
                bits -= 8;
            }
        }
        return java.util.Arrays.copyOf(out.array(), out.position());
    }
}
