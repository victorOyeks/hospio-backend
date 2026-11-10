package com.hospio.identity.service;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Keeps 2-step secrets encrypted at rest (AES-256-GCM, a random 96-bit nonce per secret). The key is
 * {@code hospio.auth.two-step-key} (env TWO_STEP_KEY); when that is unset it is derived from the JWT secret, so changing
 * that secret means turning 2-step on again (docs/profile.md).
 */
@Component
public class TwoStepSecrets {

    private static final SecureRandom RANDOM = new SecureRandom();
    private final SecretKeySpec key;

    public TwoStepSecrets(@Value("${hospio.auth.two-step-key:}") String configured, @Value("${jwt.secret}") String jwtSecret) {
        String material = StringUtils.hasText(configured) ? configured : "hospio-two-step:" + jwtSecret;
        try {
            key = new SecretKeySpec(MessageDigest.getInstance("SHA-256").digest(material.getBytes(StandardCharsets.UTF_8)), "AES");
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public String seal(byte[] secret) {
        try {
            byte[] nonce = new byte[12];
            RANDOM.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, nonce));
            byte[] sealed = cipher.doFinal(secret);
            return "v1:" + Base64.getEncoder().encodeToString(ByteBuffer.allocate(12 + sealed.length).put(nonce).put(sealed).array());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public byte[] open(String sealed) {
        if (sealed == null || !sealed.startsWith("v1:")) {
            throw new IllegalStateException("Unknown 2-step secret format");
        }
        try {
            byte[] all = Base64.getDecoder().decode(sealed.substring(3));
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, all, 0, 12));
            return cipher.doFinal(all, 12, all.length - 12);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("The 2-step secret can't be read with this key", e);
        }
    }
}
