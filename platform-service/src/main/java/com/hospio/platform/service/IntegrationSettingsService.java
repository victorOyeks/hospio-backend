package com.hospio.platform.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hospio.common.exception.BadRequestException;
import com.hospio.common.exception.ResourceNotFoundException;
import com.hospio.common.integrations.settings.IntegrationCatalogue;
import com.hospio.common.integrations.settings.IntegrationField;
import com.hospio.common.integrations.settings.IntegrationProvider;
import com.hospio.common.integrations.settings.IntegrationSettings;
import com.hospio.common.kafka.KafkaTopics;
import com.hospio.common.kafka.events.IntegrationChangedEvent;
import com.hospio.common.security.CurrentUser;
import com.hospio.common.security.HospioUserDetails;
import com.hospio.common.security.SecretBox;
import com.hospio.common.tenancy.Tenant;
import com.hospio.common.tenancy.TenantContext;
import com.hospio.platform.dto.IntegrationDtos.FieldView;
import com.hospio.platform.dto.IntegrationDtos.IntegrationUpdate;
import com.hospio.platform.dto.IntegrationDtos.IntegrationView;
import com.hospio.platform.entity.IntegrationSetting;
import com.hospio.platform.repository.IntegrationSettingRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

/**
 * Settings · Integrations (docs/integrations.md): third-party keys and addresses saved by a super admin, encrypted at rest
 * ({@link SecretBox}, SECRETS_KEY), shown masked, and read by the services that use them over {@code /internal/integrations}. A field
 * not saved here is the server's environment value, so nothing changes until someone saves something. Values are never logged; the
 * activity log names the fields changed, never what they were changed to.
 */
@Slf4j
@Service
public class IntegrationSettingsService {

    static final String TOPIC = KafkaTopics.PREFIX + "integration-changed";
    private static final TypeReference<LinkedHashMap<String, String>> SHOWN = new TypeReference<>() {
    };

    private final IntegrationSettingRepository rows;
    private final SecretBox box;
    private final ObjectMapper json;
    private final ActivityLogService activity;
    private final TransactionTemplate tx;

    public IntegrationSettingsService(IntegrationSettingRepository rows, SecretBox box, ObjectMapper json,
                                      ActivityLogService activity, TransactionTemplate tx) {
        this.rows = rows;
        this.box = box;
        this.json = json;
        this.activity = activity;
        this.tx = tx;
    }

    /** Every provider's card, in catalogue order; what is shown comes from the masked copy, so the list never needs the key. */
    @Transactional(readOnly = true)
    public List<IntegrationView> list() {
        Map<String, IntegrationSetting> saved = rows.findAll().stream()
                .collect(Collectors.toMap(IntegrationSetting::getProvider, r -> r, (a, b) -> a));
        return Arrays.stream(IntegrationProvider.values()).map(p -> view(p, saved.get(p.name()))).toList();
    }

    /**
     * Saves what was typed (a blank or absent value keeps the saved one) and clears what was asked (the server's value applies to it
     * again). With nothing left saved the row goes, and the provider is back on the environment entirely.
     */
    @Transactional
    public IntegrationView update(String providerName, IntegrationUpdate body) {
        IntegrationProvider p = provider(providerName);
        Map<String, String> typed = body == null || body.fields() == null ? Map.of() : body.fields();
        List<String> clear = body == null || body.clear() == null ? List.of() : body.clear();
        for (String key : typed.keySet()) {
            field(p, key);
        }
        for (String key : clear) {
            field(p, key);
            if (StringUtils.hasText(typed.get(key))) {
                throw new BadRequestException(field(p, key).label() + " can't be saved and cleared at once. Send it in one or the other");
            }
        }
        if (!box.ready()) {
            throw new BadRequestException("SECRETS_KEY isn't set on the server, so integration keys can't be saved yet. Set it (or POS_SECRETS_KEY) "
                    + "to 32 random bytes in base64 (openssl rand -base64 32) and restart platform-service");
        }
        Map<String, String> next = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : typed.entrySet()) {
            if (StringUtils.hasText(e.getValue())) {
                next.put(e.getKey(), IntegrationCatalogue.normalize(p, field(p, e.getKey()), e.getValue()));
            }
        }
        IntegrationSetting row = rows.findByProvider(p.name()).orElse(null);
        Map<String, String> before = row == null ? Map.of() : current(p, row, clear);
        Map<String, String> after = new LinkedHashMap<>(before);
        after.putAll(next);
        clear.forEach(after::remove);

        List<String> saved = new ArrayList<>();
        List<String> cleared = new ArrayList<>();
        for (IntegrationField f : p.fields()) {
            String was = before.get(f.key());
            String now = after.get(f.key());
            if (now != null && !now.equals(was)) {
                saved.add(f.key());
            } else if (now == null && was != null) {
                cleared.add(f.key());
            }
        }
        if (saved.isEmpty() && cleared.isEmpty()) {
            return view(p, row);
        }
        HospioUserDetails user = CurrentUser.require();
        if (after.isEmpty()) {
            rows.delete(row);
            row = null;
        } else {
            if (row == null) {
                row = IntegrationSetting.builder().provider(p.name()).build();
            }
            row.setSealedValues(box.seal(write(IntegrationCatalogue.build(p, after))));
            row.setMaskedValues(write(shown(p, after)));
            row.setUpdatedBy(user.getUserId());
            row.setUpdatedByName(user.getFullName());
            row = rows.save(row);
        }
        record(p, saved, cleared, user);
        log.info("Integration {} changed by {}: saved {}, cleared {}", p, user.getUserId(), saved, cleared);
        return view(p, row);
    }

    /**
     * What the portal has saved for a provider, decrypted, for the service that uses it (internal, never through the gateway to a
     * user); null when nothing is saved. Runs as the platform, whoever calls.
     */
    public IntegrationSettings saved(String providerName) {
        IntegrationProvider p = provider(providerName);
        IntegrationSetting row = TenantContext.runWith(Tenant.system("integrations"),
                () -> tx.execute(st -> rows.findByProvider(p.name()).orElse(null)));
        if (row == null) {
            return null;
        }
        // Throws when the key is missing or wrong: the caller then keeps what it read last instead of switching to its environment.
        return read(p, row);
    }

    // ---- helpers -----------------------------------------------------------------------------------------------

    public static IntegrationProvider provider(String name) {
        return IntegrationProvider.parse(name).orElseThrow(() -> new ResourceNotFoundException("There is no integration called " + name
                + ". The integrations are: " + Arrays.stream(IntegrationProvider.values()).map(Enum::name).collect(Collectors.joining(", "))));
    }

    static IntegrationField field(IntegrationProvider p, String key) {
        return p.field(key).orElseThrow(() -> new BadRequestException(p.label() + " has no field called " + key + ". Its fields are: "
                + p.fields().stream().map(IntegrationField::key).collect(Collectors.joining(", "))));
    }

    /** The saved values as text. Settings that can't be opened with this server's key can only be cleared completely. */
    private Map<String, String> current(IntegrationProvider p, IntegrationSetting row, List<String> clear) {
        try {
            return IntegrationCatalogue.values(read(p, row));
        } catch (RuntimeException e) {
            Set<String> all = p.fields().stream().map(IntegrationField::key).collect(Collectors.toSet());
            if (new HashSet<>(clear).containsAll(all)) {
                // Clearing everything needs nothing from the old values; report every saved field as cleared.
                return masked(row).keySet().stream().collect(Collectors.toMap(k -> k, k -> "?", (a, b) -> a, LinkedHashMap::new));
            }
            throw new BadRequestException("The saved " + p.label() + " settings can't be read with this server's SECRETS_KEY. Clear every "
                    + "field, then type them again");
        }
    }

    private IntegrationSettings read(IntegrationProvider p, IntegrationSetting row) {
        try {
            return json.readValue(box.open(row.getSealedValues()), p.type());
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("The saved " + p.label() + " settings couldn't be read", e);
        }
    }

    IntegrationView view(IntegrationProvider p, IntegrationSetting row) {
        Map<String, String> shown = row == null ? Map.of() : masked(row);
        List<FieldView> fields = p.fields().stream().map(f -> new FieldView(f.key(), f.label(), f.type().name(), hint(f),
                shown.get(f.key()), shown.containsKey(f.key()))).toList();
        return new IntegrationView(p.name(), p.label(), p.group(), p.testable(), row == null ? null : row.getUpdatedByName(),
                row == null || row.getUpdatedAt() == null ? null : row.getUpdatedAt().toString(), fields);
    }

    /** The field's hint and, so the admin knows what an empty field means, the server variable it falls back to. */
    static String hint(IntegrationField f) {
        String fallback = f.env() == null ? null : "Empty: the server's " + f.env() + " applies.";
        if (f.hint() == null) {
            return fallback;
        }
        return fallback == null ? f.hint() : f.hint() + " " + fallback;
    }

    static Map<String, String> shown(IntegrationProvider p, Map<String, String> values) {
        Map<String, String> out = new LinkedHashMap<>();
        for (IntegrationField f : p.fields()) {
            String v = values.get(f.key());
            if (v != null) {
                out.put(f.key(), IntegrationCatalogue.display(f, v));
            }
        }
        return out;
    }

    private Map<String, String> masked(IntegrationSetting row) {
        try {
            return json.readValue(row.getMaskedValues(), SHOWN);
        } catch (Exception e) {
            log.warn("Integration {}: the shown copy couldn't be read ({})", row.getProvider(), e.getMessage());
            return Map.of();
        }
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("The settings couldn't be written", e);
        }
    }

    private void record(IntegrationProvider p, List<String> saved, List<String> cleared, HospioUserDetails user) {
        IntegrationChangedEvent event = IntegrationChangedEvent.builder().eventId(UUID.randomUUID().toString())
                .actorId(user.getUserId()).actorName("Hospio360 · " + user.getFullName()).occurredAt(Instant.now())
                .provider(p.name()).providerLabel(p.label()).saved(saved).cleared(cleared).build();
        activity.record(TOPIC, event);
    }
}
