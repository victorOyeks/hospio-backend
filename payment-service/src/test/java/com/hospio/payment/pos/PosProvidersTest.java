package com.hospio.payment.pos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hospio.common.security.SecretBox;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hospio.common.integrations.PosProvider.Outcome;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

/** OPay's chunked RSA and signatures, Moniepoint's webhook signature, the key store, references and status mapping. */
class PosProvidersTest {

    private final ObjectMapper mapper = new ObjectMapper();

    static KeyPair rsa1024() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(1024);
        return g.generateKeyPair();
    }

    static String b64(byte[] b) {
        return Base64.getEncoder().encodeToString(b);
    }

    @Test
    void opayRequestIsSortedChunkedAndSigned() throws Exception {
        KeyPair opay = rsa1024();
        KeyPair merchant = rsa1024();
        OpayApi api = new OpayApi(mapper, "http://localhost");
        ProviderDtos.OpayCreateOrder order = new ProviderDtos.OpayCreateOrder("256625100332195", "256626080518047",
                "0123456789abcdef0123456789abcdef", "87650", "NGN", 300L, "N", "CASH_API", "ENERGY", "P260300012345");
        String paramContent = api.encryptJson(order, opay.getPublic());
        // Longer than one 117-byte block: chunked.
        assertThat(Base64.getDecoder().decode(paramContent).length).isGreaterThan(128);
        String plain = OpayApi.decrypt(Base64.getDecoder().decode(paramContent), opay.getPrivate());
        assertThat(plain).startsWith("{\"amount\":\"87650\",\"currency\":\"NGN\",\"headMerchantId\"");
        assertThat(plain).contains("\"sn\":\"P260300012345\"").doesNotContain("productInfo");
        String sign = OpayApi.sign(paramContent + 1700000000000L, merchant.getPrivate());
        assertThat(OpayApi.verify(paramContent + 1700000000000L, sign, merchant.getPublic())).isTrue();
        assertThat(OpayApi.verify(paramContent + 1700000000001L, sign, merchant.getPublic())).isFalse();
    }

    @Test
    void opayWebhookOpensOnlyWithTheRightKeys() throws Exception {
        KeyPair opay = rsa1024();
        KeyPair merchant = rsa1024();
        OpayApi api = new OpayApi(mapper, "http://localhost");
        // OPay encrypts the payload for the merchant (merchant public key) and signs with its own private key.
        String payload = "{\"outOrderNo\":\"0123456789abcdef0123456789abcdef\",\"orderNo\":\"OP-1\",\"status\":\"SUCCESS\",\"amount\":87650,\"sn\":\"P26\"}";
        Cipher c = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        c.init(Cipher.ENCRYPT_MODE, merchant.getPublic());
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] in = payload.getBytes(StandardCharsets.UTF_8);
        for (int off = 0; off < in.length; off += 117) {
            out.write(c.doFinal(in, off, Math.min(117, in.length - off)));
        }
        String paramContent = b64(out.toByteArray());
        String sign = OpayApi.sign(paramContent + "1700000000000", opay.getPrivate());
        ProviderDtos.OpayWebhookEnvelope env = new ProviderDtos.OpayWebhookEnvelope("cak", "V1.0.1", "JSON", "1700000000000", paramContent, sign);
        OpayApi.Keys right = new OpayApi.Keys("cak", b64(opay.getPublic().getEncoded()), b64(merchant.getPrivate().getEncoded()), "h", "m");
        ProviderDtos.OpayOrderData d = api.openWebhook(right, env);
        assertThat(d).isNotNull();
        assertThat(d.orderNo()).isEqualTo("OP-1");
        assertThat(d.amount()).isEqualByComparingTo("87650");
        assertThat(HotelPosRouter.opayOutcome(d.status())).isEqualTo(Outcome.APPROVED);
        OpayApi.Keys wrong = new OpayApi.Keys("cak", b64(rsa1024().getPublic().getEncoded()), b64(merchant.getPrivate().getEncoded()), "h", "m");
        assertThat(api.openWebhook(wrong, env)).isNull();
    }

    @Test
    void pkcs1PrivateKeysAreRefusedWithHowToConvert() {
        assertThatThrownBy(() -> OpayApi.privateKey("-----BEGIN RSA PRIVATE KEY-----\nabc\n-----END RSA PRIVATE KEY-----"))
                .hasMessageContaining("openssl pkcs8");
    }

    @Test
    void moniepointSignatureIsHmacOfIdTimestampAndBody() throws Exception {
        String body = "{\"data\":{\"merchantReference\":\"x\"}}";
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec("s3cret".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String sig = b64(mac.doFinal(("wh_1__1700__" + body).getBytes(StandardCharsets.UTF_8)));
        assertThat(MoniepointApi.signatureOk("s3cret", "wh_1", "1700", body, sig)).isTrue();
        assertThat(MoniepointApi.signatureOk("s3cret", "wh_1", "1700", body + " ", sig)).isFalse();
        assertThat(MoniepointApi.signatureOk("", "wh_1", "1700", body, sig)).isFalse();
        assertThat(MoniepointApi.kobo(new BigDecimal("87650.5"))).isEqualTo(8765050L);
        assertThat(HotelPosRouter.moniepointOutcome("PROCESSED", "00")).isEqualTo(Outcome.APPROVED);
        assertThat(HotelPosRouter.moniepointOutcome("PROCESSED", "51")).isEqualTo(Outcome.DECLINED);
        assertThat(HotelPosRouter.moniepointOutcome("PENDING", null)).isEqualTo(Outcome.UNKNOWN);
    }

    @Test
    void referencesRoundTripToPaymentIds() {
        String id = "16086dc2-cb82-46b7-bd10-8272c1a2d0c0";
        String ref = HotelPosRouter.ourRef(id);
        assertThat(ref).hasSize(32).doesNotContain("-");
        assertThat(HotelPosRouter.paymentId(ref)).isEqualTo(id);
        assertThat(HotelPosRouter.paymentId("TEST-123")).isNull();
    }

    @Test
    void keysAreSealedAndMasked() {
        SecretBox box = new SecretBox(b64(new byte[32]));
        String sealed = box.seal("{\"apiKey\":\"mp_live_abcdef123456\"}");
        assertThat(sealed).startsWith("v1:").doesNotContain("mp_live");
        assertThat(box.open(sealed)).isEqualTo("{\"apiKey\":\"mp_live_abcdef123456\"}");
        assertThat(box.seal("same")).isNotEqualTo(box.seal("same"));
        assertThat(SecretBox.mask("mp_live_abcdef123456")).isEqualTo("••••••3456");
        assertThat(SecretBox.mask("12345")).isEqualTo("••••••");
        assertThat(new SecretBox("").ready()).isFalse();
    }

    @Test
    void opayPayoutAnswersMapToDebitResults() {
        var ok = FeeDebitService.payout(new OpayApi.Answer<>("00000", "OK",
                new ProviderDtos.OpayPayoutData("m", "HF-1", "OP9", 1314750L, 0L, "SUCCESS", null)), new BigDecimal("13147.50"));
        assertThat(ok.status()).isEqualTo("APPROVED");
        assertThat(ok.collected()).isEqualByComparingTo("13147.50");
        assertThat(FeeDebitService.payout(new OpayApi.Answer<>("11099", "exists", null), BigDecimal.ONE).status()).isEqualTo("PENDING");
        assertThat(FeeDebitService.payout(new OpayApi.Answer<>("C1001", "insufficient balance", null), BigDecimal.ONE).status()).isEqualTo("FAILED");
        assertThat(FeeDebitService.payout(new OpayApi.Answer<>("00000", "OK",
                new ProviderDtos.OpayPayoutData("m", "HF-1", "OP9", 1L, 0L, "FAILED", "limit")), BigDecimal.ONE).status()).isEqualTo("FAILED");
    }
}
