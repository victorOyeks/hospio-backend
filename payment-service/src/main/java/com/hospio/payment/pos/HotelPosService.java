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
 * Each hotel's POS provider keys (docs/pos-providers.md): a hotel can hold keys for Moniepoint and OPay side by side. Each card
 * payment uses the keys of its terminal's provider; the daily fee debit uses the provider chosen for it. Hospio admins set the
 * keys; the hotel's managers and owner see them masked. Keys are encrypted at rest (SecretBox). A field sent blank keeps its saved
 * value, so one key can be replaced on its own. The hotel's POS pause lives in hotel_pos_configs.
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
    private final HotelPosKeyRepository keys;
    private final SecretBox box;
    private final ObjectMapper mapper;

    /** One provider's keys as people see them: each field masked (null when not set) and whether they're complete. */
    public record ProviderKeys(String provider, Map<String, String> fields, boolean complete, String updatedByName, Instant updatedAt) {
    }

    /** The hotel's providers with keys, and whether POS push is paused. */
    public record View(String hotelId, List<ProviderKeys> providers, boolean posPaused, boolean keyStoreReady) {
    }

    @Transactional(readOnly = true)
    public View view(String hotelId) {
        boolean paused = configs.findByHotelId(hotelId).map(HotelPosConfig::isPosPaused).orElse(false);
        List<ProviderKeys> out = keys.findByHotelIdOrderByProvider(hotelId).stream().map(this::shown).toList();
        return new View(hotelId, out, paused, box.ready());
    }

    private ProviderKeys shown(HotelPosKey k) {
        Map<String, String> masked = readMap(k.getMasked());
        Map<String, String> fields = new LinkedHashMap<>();
        for (String f : FIELDS.getOrDefault(k.getProvider(), List.of())) {
            fields.put(f, masked.get(f));
        }
        return new ProviderKeys(k.getProvider(), fields, complete(k.getProvider(), masked), k.getUpdatedByName(), k.getUpdatedAt());
    }

    /** Every hotel's POS status for the admin portal's lists: the providers whose keys are complete, and the pause. Never any key. */
    public record Status(String hotelId, List<String> providers, boolean posPaused) {
    }

    @Transactional(readOnly = true)
    public List<Status> statuses() {
        Map<String, List<String>> byHotel = new LinkedHashMap<>();
        for (HotelPosKey k : keys.findAll()) {
            if (complete(k.getProvider(), readMap(k.getMasked()))) {
                byHotel.computeIfAbsent(k.getHotelId(), x -> new java.util.ArrayList<>()).add(k.getProvider());
            }
        }
        Map<String, Boolean> paused = new LinkedHashMap<>();
        configs.findAll().forEach(c -> paused.put(c.getHotelId(), c.isPosPaused()));
        java.util.Set<String> ids = new java.util.TreeSet<>(byHotel.keySet());
        paused.forEach((h, p) -> {
            if (p) {
                ids.add(h);
            }
        });
        return ids.stream().map(h -> new Status(h, byHotel.getOrDefault(h, List.of()).stream().sorted().toList(), paused.getOrDefault(h, false)))
                .toList();
    }

    @Transactional
    public View save(String businessId, String hotelId, String provider, Map<String, String> values) {
        String p = providerOf(provider);
        if (!box.ready()) {
            throw new BadRequestException("POS_SECRETS_KEY isn't set on the server, so POS keys can't be stored yet");
        }
        HotelPosKey k = keys.findByHotelIdAndProvider(hotelId, p).orElse(null);
        Map<String, String> secrets = k == null ? new LinkedHashMap<>() : readMap(box.open(k.getSecrets()));
        for (String f : FIELDS.get(p)) {
            String v = values == null ? null : values.get(f);
            if (StringUtils.hasText(v)) {
                secrets.put(f, v.strip());
            }
        }
        secrets.keySet().retainAll(FIELDS.get(p));
        if (secrets.isEmpty()) {
            throw new BadRequestException("Enter at least one key");
        }
        if (OPAY.equals(p)) {
            checkOpayKeys(secrets);
        }
        Map<String, String> masked = new LinkedHashMap<>();
        secrets.forEach((key, v) -> masked.put(key, SecretBox.mask(v)));
        if (k == null) {
            k = HotelPosKey.builder().businessId(businessId).hotelId(hotelId).provider(p).build();
        }
        k.setSecrets(box.seal(writeMap(secrets)));
        k.setMasked(writeMap(masked));
        k.setUpdatedByName(CurrentUser.get().map(u -> u.getFullName()).orElse("Hospio360"));
        keys.save(k);
        return view(hotelId);
    }

    /** Removes one provider's keys: payments on that provider's terminals go back to the server's POS setting. The pause stays. */
    @Transactional
    public View remove(String hotelId, String provider) {
        keys.findByHotelIdAndProvider(hotelId, providerOf(provider)).ifPresent(keys::delete);
        return view(hotelId);
    }

    static String providerOf(String provider) {
        String p = provider == null ? null : provider.trim().toUpperCase(Locale.ROOT);
        if (p == null || !FIELDS.containsKey(p)) {
            throw new BadRequestException("The provider is MONIEPOINT or OPAY");
        }
        return p;
    }

    /** The provider a terminal belongs to (its provider field, any case), or null when it isn't Moniepoint or OPay. */
    public static String terminalProvider(String raw) {
        String p = raw == null ? null : raw.trim().toUpperCase(Locale.ROOT);
        return p != null && FIELDS.containsKey(p) ? p : null;
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

    /** The hotel's decrypted keys for {@code provider}, when complete. */
    public Optional<Credentials> credentials(String hotelId, String provider) {
        String p = terminalProvider(provider);
        if (hotelId == null || p == null || !box.ready()) {
            return Optional.empty();
        }
        return keys.findByHotelIdAndProvider(hotelId, p).map(k -> {
            Map<String, String> s = readMap(box.open(k.getSecrets()));
            return complete(p, s) ? new Credentials(k.getHotelId(), k.getBusinessId(), p, s) : null;
        });
    }

    /** The providers this hotel has complete keys for. */
    @Transactional(readOnly = true)
    public List<String> providersWithKeys(String hotelId) {
        return keys.findByHotelIdOrderByProvider(hotelId).stream().filter(k -> complete(k.getProvider(), readMap(k.getMasked())))
                .map(HotelPosKey::getProvider).toList();
    }

    /** Every hotel with complete keys for {@code provider} (the OPay webhook finds its hotel by which keys open it). */
    public List<Credentials> all(String provider) {
        if (!box.ready()) {
            return List.of();
        }
        return TenantContext.runWith(Tenant.system("pos-config"), () -> keys.findByProvider(provider).stream()
                .map(k -> new Credentials(k.getHotelId(), k.getBusinessId(), k.getProvider(), readMap(box.open(k.getSecrets()))))
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
