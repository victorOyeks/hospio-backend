package com.hospio.hotelconfig.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hospio.common.enums.Role;
import com.hospio.common.exception.BadRequestException;
import com.hospio.common.exception.ExternalServiceException;
import com.hospio.common.exception.ForbiddenException;
import com.hospio.common.exception.ResourceNotFoundException;
import com.hospio.common.kafka.EventPublisher;
import com.hospio.common.kafka.KafkaTopics;
import com.hospio.common.kafka.events.SettingChangedEvent;
import com.hospio.common.security.CurrentUser;
import com.hospio.common.security.HospioUserDetails;
import com.hospio.common.setup.HotelSetup;
import com.hospio.common.setup.SetupCatalogue;
import com.hospio.common.setup.SetupListEntry;
import com.hospio.common.setup.SetupSection;
import com.hospio.common.setup.SetupTemplateSettings;
import com.hospio.common.tenancy.Tenant;
import com.hospio.common.tenancy.TenantContext;
import com.hospio.hotelconfig.client.TenantClient;
import com.hospio.hotelconfig.dto.ApplyTemplateRequest;
import com.hospio.hotelconfig.entity.Setting;
import com.hospio.hotelconfig.repository.SettingRepository;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Hotel setup. Nothing is hard-coded in the other services: every rule, list and price the hotel runs
 * on lives here and they read it. Reads lay the hotel's stored values over the {@link SetupCatalogue}
 * defaults, so a brand-new hotel already answers fully. Taxes, currency and approval limits are set by
 * the owner (or by Hospio360 applying a template during onboarding), never by a manager.
 *
 * Each key is one {@link Setting} row whose value is the key's JSON text ({@code "14:00"}, {@code 7.5}, {@code true}); the
 * rows are read into and written from the typed {@link SetupSection} records.
 */
@Service
@RequiredArgsConstructor
public class HotelSetupService {

    public static final Set<String> OWNER_ONLY_SECTIONS = SetupCatalogue.OWNER_ONLY_SECTIONS;

    static final String DEPOSIT_MESSAGE = "The deposit is a share of the stay: between 0 and 100 %";
    static final String TILL_FLOAT_MESSAGE = "The till float can't be negative";

    private final SettingRepository settings;
    private final ListsAndPricesService lists;
    private final TenantClient tenantClient;
    private final ObjectMapper objectMapper;
    private final EventPublisher events;

    @Transactional(readOnly = true)
    public SetupSection section(String section) {
        return section(TenantContext.requireHotel(), section);
    }

    /** One section: the hotel's stored values laid over the catalogue defaults. */
    @Transactional(readOnly = true)
    public SetupSection section(String hotelId, String section) {
        String sec = section.toUpperCase();
        Class<? extends SetupSection> type = typeOf(sec);
        Map<String, String> stored = new HashMap<>();
        settings.findByHotelIdAndSection(hotelId, sec).forEach(s -> stored.put(s.getKey(), s.getValue()));
        SetupSection out = withDefaults(type, fromStored(objectMapper, type, stored));
        // The fee rate is the business's, read live (never stored): the guest service charge always matches the fee.
        if (out instanceof SetupSection.Payments p) {
            return p.withPlatformFeePct(tenantClient.feeRate(hotelId).orElse(null));
        }
        // A lounge, bar or restaurant has no rooms to charge to (docs/plan-venue-type.md): off on every read, whatever is stored.
        if (out instanceof SetupSection.Outlets o && Boolean.TRUE.equals(o.roomCharge()) && !tenantClient.hasAccommodation(hotelId)) {
            return withoutRoomCharge(o);
        }
        return out;
    }

    static SetupSection.Outlets withoutRoomCharge(SetupSection.Outlets o) {
        return o.withRoomCharge(false);
    }

    /** Every catalogue section, the hotel's stored values laid over the defaults. */
    @Transactional(readOnly = true)
    public HotelSetup all(String hotelId) {
        HotelSetup all = SetupCatalogue.withDefaults(stored(objectMapper, settings.findByHotelId(hotelId)));
        // As for one section: Payments carries the business's live fee rate (Hotel setup names it next to the guest-fee switch).
        HotelSetup withRate = withFeeRate(all, tenantClient.feeRate(hotelId).orElse(null));
        SetupSection.Outlets outlets = withRate.get(SetupCatalogue.OUTLETS) instanceof SetupSection.Outlets o ? o : null;
        if (outlets != null && Boolean.TRUE.equals(outlets.roomCharge()) && !tenantClient.hasAccommodation(hotelId)) {
            SetupSection.Outlets off = withoutRoomCharge(outlets);
            return HotelSetup.of(name -> SetupCatalogue.OUTLETS.equals(name) ? off : withRate.get(name));
        }
        return withRate;
    }

    /** The setup with Payments' {@code platformFeePct} set to the business's fee rate (never stored). */
    static HotelSetup withFeeRate(HotelSetup all, java.math.BigDecimal rate) {
        if (all.payments() == null) {
            return all;
        }
        SetupSection.Payments payments = all.payments().withPlatformFeePct(rate);
        return HotelSetup.of(name -> SetupCatalogue.PAYMENTS.equals(name) ? payments : all.get(name));
    }

    /** Managers and owners edit; owner-only sections need the owner; the platform may write anything (onboarding). */
    @Transactional
    public SetupSection save(String section, String body) {
        HospioUserDetails caller = CurrentUser.require();
        String sec = section.toUpperCase();
        if (!caller.isPlatform()) {
            if (OWNER_ONLY_SECTIONS.contains(sec) && !caller.has(Role.OWNER)) {
                throw new ForbiddenException("Only the owner can change " + sec.toLowerCase().replace('_', ' '));
            }
            if (!caller.isManagerOrOwner()) {
                throw new ForbiddenException("Only a manager or the owner can change Hotel setup");
            }
        }
        SetupSection sent = readSent(objectMapper, typeOf(sec), body);
        if (sent instanceof SetupSection.Payments p) {
            // Who pays the Hospio fee is the owner's decision; the fee rate is never stored here (it is the business's, read live).
            // A manager saving the section sends the switch as it is; only changing it needs the owner.
            if (p.guestPaysPlatformFee() != null && !caller.isPlatform() && !caller.has(Role.OWNER)) {
                SetupSection current = section(TenantContext.requireHotel(), SetupCatalogue.PAYMENTS);
                boolean now = current instanceof SetupSection.Payments c && Boolean.TRUE.equals(c.guestPaysPlatformFee());
                if (now != p.guestPaysPlatformFee()) {
                    throw new ForbiddenException("Only the owner decides whether guests pay the Hospio fee");
                }
            }
            sent = p.withPlatformFeePct(null);
        }
        final SetupSection values = sent;
        checkValues(values);
        String hotelId = TenantContext.requireHotel();
        return withHotelTenant(hotelId, () -> apply(hotelId, sec, values, caller.getUserId()));
    }

    /**
     * A section as the web app sent it ({@code {"depositPct": 20, ...}}): keys it leaves out stay as they are, keys the catalogue
     * doesn't know are ignored, and a value of the wrong kind is refused (a deposit or till float that is not a number gets its
     * own message, as before).
     */
    static <T extends SetupSection> T readSent(ObjectMapper objectMapper, Class<T> type, String body) {
        if (!StringUtils.hasText(body)) {
            throw new BadRequestException("Send the section's values as a JSON object");
        }
        try {
            T values = objectMapper.readValue(body, type);
            if (values == null) {
                throw new BadRequestException("Send the section's values as a JSON object");
            }
            return values;
        } catch (JsonMappingException e) {
            String key = e.getPath().isEmpty() ? null : e.getPath().get(e.getPath().size() - 1).getFieldName();
            if (type == SetupSection.Payments.class && "depositPct".equals(key)) {
                throw new BadRequestException(DEPOSIT_MESSAGE);
            }
            if (type == SetupSection.Payments.class && "tillFloat".equals(key)) {
                throw new BadRequestException(TILL_FLOAT_MESSAGE);
            }
            throw new BadRequestException(key == null ? "Send the section's values as a JSON object" : "The value of " + key + " is not valid");
        } catch (JsonProcessingException e) {
            throw new BadRequestException("Send the section's values as a JSON object");
        }
    }

    /**
     * Values that can't be right are refused before anything is saved: the deposit is a share of the stay (0 to 100 %; a
     * hotel had "15000" saved, meant as naira), and the till float is a sum of cash, never negative.
     */
    static void checkValues(SetupSection values) {
        if (!(values instanceof SetupSection.Payments p)) {
            return;
        }
        BigDecimal deposit = p.depositPct();
        if (deposit != null && (deposit.signum() < 0 || deposit.compareTo(BigDecimal.valueOf(100)) > 0)) {
            throw new BadRequestException(DEPOSIT_MESSAGE);
        }
        if (p.tillFloat() != null && p.tillFloat().signum() < 0) {
            throw new BadRequestException(TILL_FLOAT_MESSAGE);
        }
    }

    /** Hotel setup "Apply a template" and onboarding J8: by template id (from tenant-service) or inline settings. */
    @Transactional
    public HotelSetup applyTemplate(ApplyTemplateRequest request) {
        HospioUserDetails caller = CurrentUser.require();
        if (!caller.isPlatform() && !caller.isManagerOrOwner()) {
            throw new ForbiddenException("Only a manager, the owner or Hospio360 can apply a template");
        }
        String hotelId = TenantContext.requireHotel();
        SetupTemplateSettings template;
        if ("VENUE".equalsIgnoreCase(request.builtIn())) {
            if (tenantClient.hasAccommodation(hotelId)) {
                throw new BadRequestException("The lounge, bar and restaurant template is for a location without rooms");
            }
            return withHotelTenant(hotelId, () -> {
                applyVenueTemplate(hotelId, caller.getUserId());
                return all(hotelId);
            });
        }
        if (request.settings() != null) {
            template = request.settings();
        } else if (StringUtils.hasText(request.templateId())) {
            template = tenantClient.template(request.templateId())
                    .orElseThrow(() -> new ResourceNotFoundException("Template not found"));
        } else {
            throw new BadRequestException("Send a templateId or the settings to apply");
        }
        return withHotelTenant(hotelId, () -> {
            applyTemplate(hotelId, template, caller.getUserId());
            return all(hotelId);
        });
    }

    /** Service-to-service: tenant-service's onboarding applies a template to a hotel. */
    @Transactional
    public void applyTemplate(String hotelId, SetupTemplateSettings template) {
        if (template == null) {
            throw new BadRequestException("A template is a JSON object of sections");
        }
        withHotelTenant(hotelId, () -> {
            applyTemplate(hotelId, template, "template");
            return null;
        });
    }

    /**
     * The built-in "Lounge / bar / restaurant" template on a location that already has settings (one set up as a hotel first): the
     * venue values are written, the venue list entries added, and the hotel catalogue's own default entries that a venue has no use
     * for ("No-show", "Booked elsewhere") are hidden. Entries the business added itself are kept.
     */
    private void applyVenueTemplate(String hotelId, String by) {
        applyTemplate(hotelId, SetupCatalogue.venueTemplate(), by);
        SetupCatalogue.VENUE_LISTS.forEach((listName, wanted) -> {
            Set<String> keep = new HashSet<>();
            wanted.forEach(w -> keep.add(w.code()));
            List<String> hotelOnly = SetupCatalogue.LISTS.getOrDefault(listName, List.of()).stream().map(SetupCatalogue.ListDefault::code)
                    .filter(code -> !keep.contains(code)).toList();
            lists.hide(hotelId, listName, hotelOnly);
        });
    }

    /** Sections and lists of a template: {"sections": {...}, "lists": {...}} or the flat {SECTION: {...}}. */
    private void applyTemplate(String hotelId, SetupTemplateSettings template, String by) {
        HotelSetup sections = template.sectionsToApply();
        if (sections != null) {
            for (String name : SetupCatalogue.SECTIONS.keySet()) {
                SetupSection values = sections.get(name);
                if (values != null) {
                    // A template is checked like a save from Hotel setup: a deposit of 15000 % never reaches a hotel this way.
                    if (values instanceof SetupSection.Payments p) {
                        values = p.withPlatformFeePct(null);
                    }
                    checkValues(values);
                    apply(hotelId, name, values, by);
                }
            }
        }
        template.listsToApply().forEach((listName, entries) -> {
            List<ListsAndPricesService.SeedItem> items = new ArrayList<>();
            int i = 0;
            for (SetupListEntry entry : entries == null ? List.<SetupListEntry>of() : entries) {
                i++;
                if (entry == null) {
                    continue;
                }
                items.add(new ListsAndPricesService.SeedItem(entry.code(), entry.label() == null ? "" : entry.label(),
                        entry.sortOrder() != null ? entry.sortOrder() : i));
            }
            lists.ensureItems(hotelId, listName.toUpperCase(), items);
        });
    }

    /**
     * A new hotel's defaults (HotelCreated): every catalogue key the hotel lacks and every list that is
     * empty. Idempotent, quiet (no SettingChanged per key) and run inside the hotel's tenant by the caller.
     */
    @Transactional
    public void createDefaults(String hotelId) {
        createDefaults(hotelId, null);
    }

    /**
     * {@code venueType} from HotelCreated: a location without accommodation starts with charge-to-room off (docs/plan-venue-type.md).
     */
    @Transactional
    public void createDefaults(String hotelId, String venueType) {
        Set<String> present = new HashSet<>();
        settings.findByHotelId(hotelId).forEach(s -> present.add(s.getSection() + "/" + s.getKey()));
        // A lounge, bar or restaurant starts from the venue defaults (phase 3): nothing is stored for the stay keys, so a location
        // that later gains rooms reads the hotel defaults for those (charge to a room is off by capability until then).
        boolean accommodation = com.hospio.common.venue.VenueType.hasAccommodation(venueType, 0);
        HotelSetup defaults = SetupCatalogue.defaultsFor(accommodation);
        SetupCatalogue.SECTIONS.keySet().forEach(section -> {
            SetupSection values = defaults.get(section);
            if (values == null) {
                return;
            }
            SetupCatalogue.present(values).forEach(v -> {
                if (present.add(section + "/" + v.key())) {
                    settings.save(Setting.builder().hotelId(hotelId).section(section).key(v.key()).value(write(v.value()))
                            .updatedBy(accommodation ? "defaults" : "venue defaults").build());
                }
            });
        });
        SetupCatalogue.listsFor(accommodation).forEach((listName, items) -> {
            List<ListsAndPricesService.SeedItem> seed = new ArrayList<>();
            for (int i = 0; i < items.size(); i++) {
                seed.add(new ListsAndPricesService.SeedItem(items.get(i).code(), items.get(i).label(), i + 1));
            }
            lists.ensureItems(hotelId, listName, seed);
        });
    }

    /**
     * Platform callers carry no business, and every row here needs one: resolve the hotel's business from
     * tenant-service and run as the system inside it. Business and hotel callers run as themselves.
     */
    <T> T withHotelTenant(String hotelId, Supplier<T> work) {
        Tenant current = TenantContext.current().orElse(null);
        if (current != null && current.businessId() != null) {
            return work.get();
        }
        String businessId = tenantClient.businessOf(hotelId)
                .orElseThrow(() -> new ExternalServiceException("Could not find which business hotel " + hotelId + " belongs to"));
        Tenant bound = Tenant.systemFor(businessId, hotelId, current == null ? null : current.actorId(), current == null ? null : current.actorName());
        return TenantContext.runWith(bound, work);
    }

    /** Stores every key {@code values} carries (keys it leaves out are untouched) and announces each change. */
    private SetupSection apply(String hotelId, String section, SetupSection values, String by) {
        for (SetupCatalogue.Value v : SetupCatalogue.present(values)) {
            Setting setting = settings.findByHotelIdAndSectionAndKey(hotelId, section, v.key())
                    .orElseGet(() -> Setting.builder().hotelId(hotelId).section(section).key(v.key()).build());
            String old = setting.getValue();
            String fresh = write(v.value());
            if (old != null && old.equals(fresh)) {
                continue;
            }
            setting.setValue(fresh);
            setting.setUpdatedBy(by);
            settings.save(setting);
            events.publish(KafkaTopics.SETTING_CHANGED, SettingChangedEvent.builder()
                    .hotelId(hotelId).section(section).key(v.key()).oldValue(old).newValue(fresh).build());
        }
        return section(hotelId, section);
    }

    // ---- stored rows ↔ typed sections ------------------------------------------------------------------

    /** The stored rows as a partial setup: sections and keys the hotel has no row for are null; unknown sections and keys are ignored. */
    static HotelSetup stored(ObjectMapper objectMapper, List<Setting> rows) {
        Map<String, Map<String, String>> bySection = new HashMap<>();
        for (Setting s : rows) {
            bySection.computeIfAbsent(s.getSection(), k -> new HashMap<>()).put(s.getKey(), s.getValue());
        }
        return HotelSetup.of(name -> {
            Map<String, String> raw = bySection.get(name);
            return raw == null ? null : fromStored(objectMapper, SetupCatalogue.SECTIONS.get(name), raw);
        });
    }

    /**
     * One section from its rows (key → JSON text). A value that can't be read as its key's type (a row written by hand, or text
     * saved before it was JSON) counts as not stored, so the default applies; text that isn't JSON is kept for a text key.
     */
    static <T extends SetupSection> T fromStored(ObjectMapper objectMapper, Class<T> type, Map<String, String> raw) {
        return SetupCatalogue.build(type, (key, javaType) -> readStored(objectMapper, raw.get(key), javaType));
    }

    private static Object readStored(ObjectMapper objectMapper, String json, Type type) {
        if (json == null) {
            return null;
        }
        try {
            return objectMapper.readValue(json, objectMapper.constructType(type));
        } catch (JsonProcessingException e) {
            return type == String.class ? json : null;
        }
    }

    @SuppressWarnings("unchecked")
    private static <T extends SetupSection> SetupSection withDefaults(Class<T> type, SetupSection stored) {
        return SetupCatalogue.withDefaults(type, (T) stored);
    }

    private static Class<? extends SetupSection> typeOf(String section) {
        return SetupCatalogue.typeOf(section).orElseThrow(() -> new BadRequestException("Hotel setup has no section " + section));
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new BadRequestException("Value could not be stored");
        }
    }
}
