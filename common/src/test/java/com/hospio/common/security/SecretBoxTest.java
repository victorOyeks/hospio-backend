package com.hospio.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Base64;
import org.junit.jupiter.api.Test;

/** SecretBox moved from payment-service to common: the same key opens what the old class sealed (the hotels' POS keys on staging). */
class SecretBoxTest {

    /** Bytes 0..31, base64. */
    private static final String KEY = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=";
    /** Sealed by payment-service's SecretBox before the move (git show 97d0b15:payment-service/…/pos/SecretBox.java), with KEY. */
    private static final String SEALED_BEFORE_THE_MOVE = "v1:MsO+Z4f0+gPQNtl8ItmFOobyj8+oT7NWNUC+cBErJSX6eMNWaX+xCOqxtVcPvUJ97DnWYEbBFZpLFIYo4XUDiJKdTdcOLlh0oxbVYaBGgFnFmS3RYyK5VLPDN4Ddvw0=";

    @Test
    void aValueSealedByTheOldPaymentServiceClassStillOpens() {
        assertThat(new SecretBox(KEY).open(SEALED_BEFORE_THE_MOVE))
                .isEqualTo("{\"apiKey\":\"mp_live_abcdef123456\",\"webhookSecret\":\"whsec_staging_1\"}");
    }

    @Test
    void sealsAndOpensWithAFreshIvEachTime() {
        SecretBox box = new SecretBox(KEY);
        String a = box.seal("sk_live_1");
        assertThat(a).startsWith("v1:").isNotEqualTo(box.seal("sk_live_1"));
        assertThat(box.open(a)).isEqualTo("sk_live_1");
    }

    @Test
    void aWrongKeyOrNoKeyIsRefusedWithoutSayingWhatTheValueWas() {
        String sealed = new SecretBox(KEY).seal("sk_live_secret");
        SecretBox other = new SecretBox(Base64.getEncoder().encodeToString(new byte[32]));
        assertThatThrownBy(() -> other.open(sealed)).hasMessageContaining("couldn't be read").hasMessageNotContaining("sk_live");
        assertThat(new SecretBox("").ready()).isFalse();
        assertThatThrownBy(() -> new SecretBox(" ").seal("x")).hasMessageContaining("SECRETS_KEY");
        assertThatThrownBy(() -> new SecretBox(Base64.getEncoder().encodeToString(new byte[16]))).hasMessageContaining("32 bytes");
    }

    @Test
    void masksAllButTheEnd() {
        assertThat(SecretBox.mask("mp_live_abcdef123456")).isEqualTo("••••••3456");
        assertThat(SecretBox.mask("abcdefg")).isEqualTo("••••••fg");
        assertThat(SecretBox.mask("12345")).isEqualTo("••••••");
        assertThat(SecretBox.mask(" ")).isNull();
    }
}
