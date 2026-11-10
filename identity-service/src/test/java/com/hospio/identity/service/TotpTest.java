package com.hospio.identity.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** RFC 6238 appendix B test vectors (8 digits, the three hashes with their own seeds), the 6-digit app form and base32. */
class TotpTest {

    private static final byte[] SHA1 = "12345678901234567890".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] SHA256 = "12345678901234567890123456789012".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] SHA512 = "1234567890123456789012345678901234567890123456789012345678901234".getBytes(StandardCharsets.US_ASCII);

    private static String at(byte[] key, long time, String alg) {
        return Totp.code(key, Totp.step(time), 8, alg);
    }

    @Test
    void rfc6238Vectors() {
        long[] times = {59L, 1111111109L, 1111111111L, 1234567890L, 2000000000L, 20000000000L};
        String[] sha1 = {"94287082", "07081804", "14050471", "89005924", "69279037", "65353130"};
        String[] sha256 = {"46119246", "68084774", "67062674", "91819424", "90698825", "77737706"};
        String[] sha512 = {"90693936", "25091201", "99943326", "93441116", "38618901", "47863826"};
        for (int i = 0; i < times.length; i++) {
            assertThat(at(SHA1, times[i], "HmacSHA1")).as("SHA1 at %d", times[i]).isEqualTo(sha1[i]);
            assertThat(at(SHA256, times[i], "HmacSHA256")).as("SHA256 at %d", times[i]).isEqualTo(sha256[i]);
            assertThat(at(SHA512, times[i], "HmacSHA512")).as("SHA512 at %d", times[i]).isEqualTo(sha512[i]);
        }
    }

    @Test
    void appCodesAreTheLastSixDigitsAndAllowOneStepOfDriftOnce() {
        assertThat(Totp.codeAt(SHA1, 59L)).isEqualTo("287082");
        long now = 1111111111L;
        String code = Totp.codeAt(SHA1, now);
        assertThat(Totp.matchingStep(SHA1, code, now, null)).hasValue(Totp.step(now));
        assertThat(Totp.matchingStep(SHA1, code, now + 30, null)).hasValue(Totp.step(now));
        assertThat(Totp.matchingStep(SHA1, code, now - 30, null)).hasValue(Totp.step(now));
        assertThat(Totp.matchingStep(SHA1, code, now + 90, null)).isEmpty();
        // Used once: the same step (or an earlier one) is never accepted again.
        assertThat(Totp.matchingStep(SHA1, code, now, Totp.step(now))).isEmpty();
        assertThat(Totp.matchingStep(SHA1, "12345", now, null)).isEmpty();
        assertThat(Totp.matchingStep(SHA1, "abcdef", now, null)).isEmpty();
        assertThat(Totp.matchingStep(SHA1, code.substring(0, 3) + " " + code.substring(3), now, null)).isPresent();
    }

    @Test
    void base32RoundTripsAndMatchesRfc4648() {
        assertThat(Totp.base32("foobar".getBytes(StandardCharsets.US_ASCII))).isEqualTo("MZXW6YTBOI");
        assertThat(Totp.base32(SHA1)).isEqualTo("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ");
        byte[] secret = Totp.newSecret();
        assertThat(secret).hasSize(20);
        assertThat(Totp.fromBase32(Totp.base32(secret))).isEqualTo(secret);
        assertThat(Totp.fromBase32("gezd gnbv gy3t qojq gezd gnbv gy3t qojq")).isEqualTo(SHA1);
    }
}
