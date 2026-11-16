package com.hospio.payment.pos;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Encrypts the hotels' POS keys at rest: AES-256-GCM, a fresh 12-byte IV per value, stored as base64(iv || ciphertext+tag) with a
 * "v1:" prefix. The key is POS_SECRETS_KEY (32 bytes, base64) in the server's environment; without it no keys can be saved or read.
 */
@Component
public class SecretBox {

    private static final SecureRandom RANDOM = new SecureRandom();
    private final byte[] key;

    public SecretBox(@Value("${hospio.pos.secrets-key:}") String base64Key) {
        byte[] k = null;
        if (base64Key != null && !base64Key.isBlank()) {
            k = Base64.getDecoder().decode(base64Key.trim());
            if (k.length != 32) {
                throw new IllegalStateException("POS_SECRETS_KEY must be 32 bytes, base64 (openssl rand -base64 32)");
            }
        }
        this.key = k;
    }

    public boolean ready() {
        return key != null;
    }

    public String seal(String plain) {
        requireKey();
        try {
            byte[] iv = new byte[12];
            RANDOM.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
            byte[] out = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            return "v1:" + Base64.getEncoder().encodeToString(ByteBuffer.allocate(iv.length + out.length).put(iv).put(out).array());
        } catch (Exception e) {
            throw new IllegalStateException("The keys couldn't be encrypted", e);
        }
    }

    public String open(String sealed) {
        requireKey();
        if (sealed == null || !sealed.startsWith("v1:")) {
            throw new IllegalStateException("Stored keys are in an unknown format");
        }
        try {
            byte[] all = Base64.getDecoder().decode(sealed.substring(3));
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, all, 0, 12));
            return new String(c.doFinal(all, 12, all.length - 12), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("The stored keys couldn't be read (wrong POS_SECRETS_KEY?)", e);
        }
    }

    private void requireKey() {
        if (key == null) {
            throw new IllegalStateException("POS_SECRETS_KEY isn't set on the server, so POS keys can't be stored");
        }
    }

    /** What people see of a value: its last 4 characters (fewer for short values), the rest dotted. */
    public static String mask(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String v = value.strip();
        int show = v.length() >= 12 ? 4 : v.length() >= 6 ? 2 : 0;
        return "••••••" + v.substring(v.length() - show);
    }
}
