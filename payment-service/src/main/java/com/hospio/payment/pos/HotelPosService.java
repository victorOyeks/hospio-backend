package com.hospio.payment.pos;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hospio.common.exception.BadRequestException;
import com.hospio.common.security.CurrentUser;
import com.hospio.common.security.SecretBox;
import com.hospio.common.tenancy.Tenant;
import com.hospio.common.tenancy.TenantContext;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Each hotel's POS provider and keys (docs/pos-providers.md). Hospio admins set them; the hotel's managers and owner see them masked.
 * Keys are encrypted at rest (SecretBox). A field sent blank keeps its saved value, so one key can be replaced on its own.
 */
@Service
@RequiredArgsConstructor
public class HotelPosService {

    public static final String MONIEPOINT = "MONIEPOINT";
    public static final String OPAY = "OPAY";

    /** The fields each provider needs, in the order the screens show them. */
    public static final Map<String, List<String>> FIELDS = Map.of(
            MONIEPOINT, List.of("apiKey", "webhookSecret"),
            OPAY, List.of("clientAuthKey", "opayPublicKey", "merchantPrivateKey", "headMerchantId", "merchantId"));

    private final HotelPosConfigRepository configs;
    private final SecretBox box;
    private final ObjectMapper mapper;

    /** What people see: the provider, each field masked (null when not set), whether it's complete, and whether POS is paused. */
    public record View(String hotelId, String provider, Map<String, String> fields, boolean complete, boolean posPaused, String updatedByName,
                       Instant updatedAt, boolean keyStoreReady) {
    }

    @Transactional(readOnly = true)
    public View view(String hotelId) {
        HotelPosConfig c = configs.findByHotelId(hotelId).orElse(null);
        if (c == null) {
            return new View(hotelId, null, Map.of(), false, false, null, null, box.ready());
        }
        if (c.getProvider() == null) {
            // No keys (never entered, or removed); the row may still hold a POS pause.
            return new View(hotelId, null, Map.of(), false, c.isPosPaused(), c.getUpdatedByName(), c.getUpdatedAt(), box.ready());
        }
        Map<String, String> masked = readMap(c.getMasked());
        Map<String, String> shown = new LinkedHashMap<>();
        for (String f : FIELDS.getOrDefault(c.getProvider(), List.of())) {
            shown.put(f, masked.get(f));
        }
        return new View(hotelId, c.getProvider(), shown, complete(c.getProvider(), masked), c.isPosPaused(), c.getUpdatedByName(),
                c.getUpdatedAt(), box.ready());
    }

    /** Every hotel's POS status for the admin portal's lists: provider, keys complete, paused. Never any key. */
    public record Status(String hotelId, String provider, boolean complete, boolean posPaused) {
    }

    @Transactional(readOnly = true)
    public List<Status> statuses() {
        return configs.findAll().stream()
                .map(c -> new Status(c.getHotelId(), c.getProvider(), c.getProvider() != null && complete(c.getProvider(), readMap(c.getMasked())),
                        c.isPosPaused()))
                .toList();
    }

    @Transactional
    public View save(String businessId, String hotelId, String provider, Map<String, String> values) {
        String p = provider == null ? null : provider.trim().toUpperCase(Locale.ROOT);
        if (p == null || !FIELDS.containsKey(p)) {
            throw new BadRequestException("The provider is MONIEPOINT or OPAY");
        }
        if (!box.ready()) {
            throw new BadRequestException("POS_SECRETS_KEY isn't set on the server, so POS keys can't be stored yet");
        }
        HotelPosConfig c = configs.findByHotelId(hotelId)
                .orElseGet(() -> HotelPosConfig.builder().businessId(businessId).hotelId(hotelId).build());
        Map<String, String> secrets = p.equals(c.getProvider()) && c.getSecrets() != null ? readMap(box.open(c.getSecrets())) : new LinkedHashMap<>();
        for (String f : FIELDS.get(p)) {
            String v = values == null ? null : values.get(f);
            if (StringUtils.hasText(v)) {
                secrets.put(f, v.strip());
            }
        }
        secrets.keySet().retainAll(FIELDS.get(p));
        if (OPAY.equals(p)) {
            checkOpayKeys(secrets);
        }
        Map<String, String> masked = new LinkedHashMap<>();
        secrets.forEach((k, v) -> masked.put(k, SecretBox.mask(v)));
        c.setProvider(p);
        c.setSecrets(box.seal(writeMap(secrets)));
        c.setMasked(writeMap(masked));
        c.setUpdatedByName(CurrentUser.get().map(u -> u.getFullName()).orElse("Hospio360"));
        configs.save(c);
        return view(hotelId);
    }

    /** Removes the hotel's provider and keys: its card payments go back to the server's POS setting. A POS pause stays as it is. */
    @Transactional
    public View remove(String hotelId) {
        configs.findByHotelId(hotelId).ifPresent(c -> {
            c.setProvider(null);
            c.setSecrets(null);
            c.setMasked(null);
            c.setUpdatedByName(CurrentUser.get().map(u -> u.getFullName()).orElse("Hospio360"));
            configs.save(c);
        });
        return view(hotelId);
    }

    /** Refuses keys that can't work: a PKCS#1 private key or one that isn't RSA, a public key that isn't X.509. */
    private static void checkOpayKeys(Map<String, String> s) {
        try {
            if (s.get("opayPublicKey") != null) {
                OpayApi.publicKey(s.get("opayPublicKey"));
            }
            if (s.get("merchantPrivateKey") != null) {
                OpayApi.privateKey(s.get("merchantPrivateKey"));
            }
        } catch (IllegalStateException e) {
            throw new BadRequestException(e.getMessage());
        }
    }

    static boolean complete(String provider, Map<String, String> fields) {
        return provider != null && FIELDS.getOrDefault(provider, List.of()).stream().allMatch(f -> fields.get(f) != null);
    }

    // ---- for the router and the debit (never leaves payment-service) ----------------------------------

    /** The hotel's provider and decrypted keys, when complete. */
    public Optional<Credentials> credentials(String hotelId) {
        return configs.findByHotelId(hotelId).filter(c -> c.getSecrets() != null && box.ready()).map(c -> {
            Map<String, String> s = readMap(box.open(c.getSecrets()));
            return complete(c.getProvider(), s) ? new Credentials(c.getHotelId(), c.getBusinessId(), c.getProvider(), s) : null;
        });
    }

    /** Every hotel with complete keys for {@code provider} (the OPay webhook finds its hotel by which keys open it). */
    public List<Credentials> all(String provider) {
        return TenantContext.runWith(Tenant.system("pos-config"), () -> configs.findByProvider(provider).stream()
                .filter(c -> c.getSecrets() != null && box.ready())
                .map(c -> new Credentials(c.getHotelId(), c.getBusinessId(), c.getProvider(), readMap(box.open(c.getSecrets()))))
                .filter(c -> complete(c.provider(), c.values())).toList());
    }

    public record Credentials(String hotelId, String businessId, String provider, Map<String, String> values) {
        public OpayApi.Keys opay() {
            return new OpayApi.Keys(values.get("clientAuthKey"), values.get("opayPublicKey"), values.get("merchantPrivateKey"),
                    values.get("headMerchantId"), values.get("merchantId"));
        }

        public MoniepointApi.Keys moniepoint() {
            return new MoniepointApi.Keys(values.get("apiKey"), values.get("webhookSecret"));
        }
    }

    @Transactional(readOnly = true)
    public boolean paused(String hotelId) {
        return configs.findByHotelId(hotelId).map(HotelPosConfig::isPosPaused).orElse(false);
    }

    @Transactional
    public void setPaused(String businessId, String hotelId, boolean paused) {
        HotelPosConfig c = configs.findByHotelId(hotelId)
                .orElseGet(() -> HotelPosConfig.builder().businessId(businessId).hotelId(hotelId).build());
        c.setPosPaused(paused);
        configs.save(c);
    }

    // ---- json ----------------------------------------------------------------------------------------

    private Map<String, String> readMap(String json) {
        if (!StringUtils.hasText(json)) {
            return new LinkedHashMap<>();
        }
        try {
            return mapper.readValue(json, new TypeReference<LinkedHashMap<String, String>>() {
            });
        } catch (Exception e) {
            throw new IllegalStateException("Stored POS keys couldn't be read", e);
        }
    }

    private String writeMap(Map<String, String> m) {
        try {
            return mapper.writeValueAsString(m);
        } catch (Exception e) {
            throw new IllegalStateException("POS keys couldn't be written", e);
        }
    }
}
