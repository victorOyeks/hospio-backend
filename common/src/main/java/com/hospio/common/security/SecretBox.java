package com.hospio.common.security;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Encrypts secrets at rest (the hotels' POS keys in payment-service, the integration keys in platform-service): AES-256-GCM, a fresh
 * 12-byte IV per value, stored as base64(iv || ciphertext+tag) with a "v1:" prefix. The master key is SECRETS_KEY (32 bytes, base64)
 * in the server's environment, else POS_SECRETS_KEY, the name it had when only POS keys used it: same key, same format, so values
 * sealed before the move still open. Without a key nothing can be saved or read.
 *
 * <p>Not a component: only the services that store secrets declare one, so an unrelated service never fails to start over a bad key.
 */
public class SecretBox {

    private static final SecureRandom RANDOM = new SecureRandom();
    private final byte[] key;

    public SecretBox(String base64Key) {
        byte[] k = null;
        if (base64Key != null && !base64Key.isBlank()) {
            k = Base64.getDecoder().decode(base64Key.trim());
            if (k.length != 32) {
                throw new IllegalStateException("SECRETS_KEY (or POS_SECRETS_KEY) must be 32 bytes, base64 (openssl rand -base64 32)");
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
            throw new IllegalStateException("The stored keys couldn't be read (wrong SECRETS_KEY / POS_SECRETS_KEY?)", e);
        }
    }

    private void requireKey() {
        if (key == null) {
            throw new IllegalStateException("SECRETS_KEY (or POS_SECRETS_KEY) isn't set on the server, so keys can't be stored or read");
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
