package com.hospio.payment.pos;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.interfaces.RSAKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.util.Base64;
import javax.crypto.Cipher;
import lombok.extern.slf4j.Slf4j;
import com.hospio.common.integrations.settings.IntegrationSettings;
import com.hospio.common.integrations.settings.IntegrationSettingsReader;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * OPay's open API (docs/pos-providers.md, ported from StationMag's OpayCryptoService and PaymentService). Every request: the JSON
 * (keys sorted at every level, nulls left out) encrypted in chunks with OPay's public key (RSA/ECB/PKCS1Padding), then
 * {@code sign = SHA256withRSA(paramContent + timestamp)} with the merchant's private key (PKCS#8). The answer's {@code data} is
 * decrypted in chunks with the merchant's private key. Each hotel's keys are passed in; nothing is held here. The base URL is saved in
 * the admin portal (Settings · Integrations · OPay POS), else OPAY_BASE_URL, read per call.
 */
@Slf4j
@Component
public class OpayApi {

    public record Keys(String clientAuthKey, String opayPublicKey, String merchantPrivateKey, String headMerchantId, String merchantId) {
    }

    /** {@code code} "00000" means OK; {@code data} is the decrypted answer (null when there was none). */
    public record Answer<T>(String code, String message, T data) {
        public boolean ok() {
            return "00000".equals(code);
        }
    }

    private static final String RSA = "RSA/ECB/PKCS1Padding";
    private static final String SIGNATURE = "SHA256withRSA";

    /** A client for one base URL, rebuilt only when the URL in force changes. */
    private record Client(String baseUrl, RestClient http) {
    }

    private final ObjectMapper mapper;
    /** Writes keys in alphabetical order at every level (records and maps), as OPay's signature expects. */
    private final ObjectMapper sorted;
    private final String envUrl;
    private IntegrationSettingsReader integrations;
    private volatile Client client;

    public OpayApi(ObjectMapper mapper, @Value("${hospio.pos.opay.base-url:https://payapi.opayweb.com/openApi}") String baseUrl) {
        this.mapper = mapper;
        this.sorted = mapper.copy().configure(com.fasterxml.jackson.databind.MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
                .configure(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
                .setSerializationInclusion(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL);
        this.envUrl = baseUrl;
    }

    @Autowired(required = false)
    public void setIntegrations(IntegrationSettingsReader integrations) {
        this.integrations = integrations;
    }

    /** The base URL in force: the portal's, else OPAY_BASE_URL. */
    public String baseUrl() {
        IntegrationSettings.Opay env = new IntegrationSettings.Opay(envUrl);
        String url = (integrations == null ? env : integrations.effective(env)).baseUrl();
        return url == null ? "" : url;
    }

    private RestClient http() {
        String url = baseUrl();
        Client c = client;
        if (c == null || !c.baseUrl().equals(url)) {
            SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
            f.setConnectTimeout((int) Duration.ofSeconds(15).toMillis());
            f.setReadTimeout((int) Duration.ofSeconds(30).toMillis());
            c = new Client(url, RestClient.builder().baseUrl(url).requestFactory(f).build());
            client = c;
        }
        return c.http();
    }

    /** Push to terminal: amount in naira as a plain string ("5000", "5000.5"); answers OPay's orderNo. */
    public Answer<ProviderDtos.OpayCreateOrderData> createOrder(Keys k, String outOrderNo, BigDecimal naira, String terminalSerial) {
        return call(k, "/order/checkout/createOrder", new ProviderDtos.OpayCreateOrder(k.headMerchantId(), k.merchantId(), outOrderNo,
                naira.stripTrailingZeros().toPlainString(), "NGN", 300L, "N", "CASH_API", "ENERGY", terminalSerial), ProviderDtos.OpayCreateOrderData.class);
    }

    public Answer<ProviderDtos.OpayOrderData> queryOrder(Keys k, String orderNo, String outOrderNo) {
        return call(k, "/order/checkout/qryOrderDetail", new ProviderDtos.OpayQueryOrder(k.headMerchantId(), k.merchantId(), orderNo,
                orderNo == null ? outOrderNo : null), ProviderDtos.OpayOrderData.class);
    }

    /** Pays out of the merchant's own OPay account (amount in kobo); {@code requestOrderNumber} is the idempotency key. */
    public Answer<ProviderDtos.OpayPayoutData> payout(Keys k, String requestOrderNumber, long kobo, String payOutType, String recipientAccount,
                                                      String recipientName, String recipientBankCode, String description) {
        return call(k, "/merchant/createMerchantPayOut", new ProviderDtos.OpayPayout(k.merchantId(), requestOrderNumber, payOutType, kobo, "NGN",
                recipientAccount, recipientName, "BANK_TRANSFER".equals(payOutType) ? recipientBankCode : null, description), ProviderDtos.OpayPayoutData.class);
    }

    public Answer<ProviderDtos.OpayPayoutData> queryPayout(Keys k, String requestOrderNumber) {
        return call(k, "/merchant/queryMerchantPayOut", new ProviderDtos.OpayQueryPayout(k.merchantId(), requestOrderNumber), ProviderDtos.OpayPayoutData.class);
    }

    <T> Answer<T> call(Keys k, String path, Object payload, Class<T> type) {
        long timestamp = System.currentTimeMillis();
        String paramContent = encryptJson(payload, publicKey(k.opayPublicKey()));
        String sign = sign(paramContent + timestamp, privateKey(k.merchantPrivateKey()));
        ProviderDtos.OpayRequest body = new ProviderDtos.OpayRequest(paramContent, sign);
        String raw = http().post().uri(path).header("Content-Type", "application/json").header("version", "V1.0.1").header("bodyFormat", "JSON")
                .header("clientAuthKey", k.clientAuthKey()).header("timestamp", String.valueOf(timestamp)).body(body).retrieve().body(String.class);
        if (raw == null || raw.isBlank()) {
            throw new IllegalStateException("OPay answered nothing");
        }
        ProviderDtos.OpayEnvelope env;
        try {
            env = mapper.readValue(raw, ProviderDtos.OpayEnvelope.class);
        } catch (Exception e) {
            throw new IllegalStateException("OPay's answer wasn't readable: " + e.getMessage(), e);
        }
        T data = null;
        if (env.data() != null && !env.data().isBlank()) {
            try {
                data = mapper.readValue(decrypt(Base64.getDecoder().decode(env.data()), privateKey(k.merchantPrivateKey())), type);
            } catch (Exception e) {
                log.warn("OPay {} answer couldn't be decrypted: {}", path, e.getMessage());
            }
        }
        return new Answer<>(env.code(), env.message(), data);
    }

    // ---- webhook -------------------------------------------------------------------------------------

    /** The webhook's decrypted payload when {@code sign} checks out against OPay's public key and the merchant key opens it; else null. */
    public ProviderDtos.OpayOrderData openWebhook(Keys k, ProviderDtos.OpayWebhookEnvelope envelope) {
        try {
            if (envelope == null || envelope.paramContent() == null || envelope.sign() == null) {
                return null;
            }
            String timestamp = envelope.timestamp() == null ? "" : envelope.timestamp();
            if (!verify(envelope.paramContent() + timestamp, envelope.sign(), publicKey(k.opayPublicKey()))) {
                return null;
            }
            return mapper.readValue(decrypt(Base64.getDecoder().decode(envelope.paramContent()), privateKey(k.merchantPrivateKey())),
                    ProviderDtos.OpayOrderData.class);
        } catch (Exception e) {
            return null;
        }
    }

    // ---- crypto (as StationMag) ----------------------------------------------------------------------

    String encryptJson(Object payload, PublicKey key) {
        try {
            String json = sorted.writeValueAsString(payload);
            Cipher c = Cipher.getInstance(RSA);
            c.init(Cipher.ENCRYPT_MODE, key);
            return Base64.getEncoder().encodeToString(chunks(json.getBytes(StandardCharsets.UTF_8), length(key) - 11, c));
        } catch (Exception e) {
            throw new IllegalStateException("OPay request couldn't be encrypted: " + e.getMessage(), e);
        }
    }

    static String decrypt(byte[] input, PrivateKey key) throws Exception {
        Cipher c = Cipher.getInstance(RSA);
        c.init(Cipher.DECRYPT_MODE, key);
        return new String(chunks(input, length(key), c), StandardCharsets.UTF_8);
    }

    static String sign(String value, PrivateKey key) {
        try {
            Signature s = Signature.getInstance(SIGNATURE);
            s.initSign(key);
            s.update(value.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(s.sign());
        } catch (Exception e) {
            throw new IllegalStateException("OPay request couldn't be signed: " + e.getMessage(), e);
        }
    }

    static boolean verify(String value, String signature, PublicKey key) {
        try {
            Signature s = Signature.getInstance(SIGNATURE);
            s.initVerify(key);
            s.update(value.getBytes(StandardCharsets.UTF_8));
            return s.verify(Base64.getDecoder().decode(signature));
        } catch (Exception e) {
            return false;
        }
    }

    private static byte[] chunks(byte[] input, int size, Cipher c) throws Exception {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (int off = 0; off < input.length; off += size) {
                out.write(c.doFinal(input, off, Math.min(size, input.length - off)));
            }
            return out.toByteArray();
        }
    }

    private static int length(Object key) {
        return (((RSAKey) key).getModulus().bitLength() + 7) / 8;
    }

    static PublicKey publicKey(String pem) {
        try {
            return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(body(pem))));
        } catch (Exception e) {
            throw new IllegalStateException("OPay public key isn't a valid X.509 RSA public key", e);
        }
    }

    static PrivateKey privateKey(String pem) {
        if (pem != null && pem.contains("BEGIN RSA PRIVATE KEY")) {
            throw new IllegalStateException("The merchant private key is PKCS#1; convert it: openssl pkcs8 -topk8 -nocrypt -in key.pem");
        }
        try {
            return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(body(pem))));
        } catch (Exception e) {
            throw new IllegalStateException("The merchant private key isn't a valid PKCS#8 RSA private key", e);
        }
    }

    /** A PEM's base64 body (or a bare base64 key): headers, quotes, literal \n and whitespace removed. */
    static String body(String pem) {
        if (pem == null) {
            throw new IllegalStateException("Key missing");
        }
        return pem.replace("\\n", "\n").replaceAll("-----(BEGIN|END)[A-Z ]+-----", "").replace("\"", "").replaceAll("\\s", "");
    }
}
