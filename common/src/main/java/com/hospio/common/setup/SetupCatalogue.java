package com.hospio.common.setup;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiFunction;

/**
 * The Hotel setup contract with the web apps: every section with its typed record ({@link SetupSection}) and defaults, and the
 * default entries of each list (docs/onboarding.md, hotel-config-service). hotel-config answers {@code GET /setup} by laying a
 * hotel's stored values over these defaults, so a brand-new hotel already answers fully; tenant-service seeds the "Standard
 * hotel" template from {@link #template()}. Only the keys listed here are kept: a key or section the catalogue does not know
 * is ignored when read and never stored.
 */
public final class SetupCatalogue {

    private SetupCatalogue() {
    }

    public static final String BASICS = "BASICS";
    public static final String BRAND = "BRAND";
    public static final String STAY_RULES = "STAY_RULES";
    public static final String PAYMENTS = "PAYMENTS";
    public static final String TAXES_CURRENCY = "TAXES_CURRENCY";
    public static final String APPROVAL_LIMITS = "APPROVAL_LIMITS";
    public static final String HOUSEKEEPING = "HOUSEKEEPING";
    public static final String OUTLETS = "OUTLETS";
    public static final String MESSAGING = "MESSAGING";
    public static final String STAFF = "STAFF";
    public static final String NIGHT_AUDIT = "NIGHT_AUDIT";
    public static final String POS = "POS";

    /** Sections only the owner may change; Hospio360 applies them through templates during onboarding. */
    public static final Set<String> OWNER_ONLY_SECTIONS = Set.of(TAXES_CURRENCY, APPROVAL_LIMITS);

    /** Section name → its record type, in the order the Hotel setup screen shows them. */
    public static final Map<String, Class<? extends SetupSection>> SECTIONS;

    public record ListDefault(String code, String label) {
    }

    /** List name → default entries, in display order. */
    public static final Map<String, List<ListDefault>> LISTS;

    static {
        Map<String, Class<? extends SetupSection>> s = new LinkedHashMap<>();
        s.put(BASICS, SetupSection.Basics.class);
        s.put(BRAND, SetupSection.Brand.class);
        s.put(STAY_RULES, SetupSection.StayRules.class);
        s.put(PAYMENTS, SetupSection.Payments.class);
        s.put(TAXES_CURRENCY, SetupSection.TaxesCurrency.class);
        s.put(APPROVAL_LIMITS, SetupSection.ApprovalLimits.class);
        s.put(HOUSEKEEPING, SetupSection.Housekeeping.class);
        s.put(OUTLETS, SetupSection.Outlets.class);
        s.put(MESSAGING, SetupSection.Messaging.class);
        s.put(STAFF, SetupSection.Staff.class);
        s.put(NIGHT_AUDIT, SetupSection.NightAudit.class);
        s.put(POS, SetupSection.Pos.class);
        SECTIONS = Collections.unmodifiableMap(s);

        Map<String, List<ListDefault>> l = new LinkedHashMap<>();
        l.put("CANCEL_REASONS", items("GUEST_CHANGED_PLANS", "Guest changed plans", "NO_SHOW", "No-show", "BOOKED_ELSEWHERE", "Booked elsewhere",
                "DUPLICATE", "Duplicate booking", "HOTEL_UNAVAILABLE", "Hotel could not honour the booking"));
        l.put("DISCOUNT_REASONS", items("LOYAL_GUEST", "Loyal guest", "CORPORATE_RATE", "Corporate rate", "SERVICE_RECOVERY", "Service recovery",
                "MANAGER_DISCRETION", "Manager's discretion"));
        l.put("VOID_REASONS", items("ENTERED_IN_ERROR", "Entered in error", "DUPLICATE_CHARGE", "Duplicate charge", "GUEST_DISPUTED", "Guest disputed"));
        l.put("WRITE_OFF_REASONS", items("UNCOLLECTABLE", "Uncollectable", "GOODWILL", "Goodwill", "DAMAGED_STOCK", "Damaged stock"));
        l.put("SHIFTS", items("MORNING", "Morning (07:00-15:00)", "AFTERNOON", "Afternoon (15:00-23:00)", "NIGHT", "Night (23:00-07:00)"));
        l.put("DEPARTMENTS", items("FRONT_OFFICE", "Front office", "HOUSEKEEPING", "Housekeeping", "LAUNDRY", "Laundry", "MAINTENANCE", "Maintenance",
                "KITCHEN", "Kitchen", "BAR", "Bar", "INVENTORY", "Inventory", "FINANCE", "Finance"));
        l.put("PUBLIC_AREAS", items("LOBBY", "Lobby", "RESTAURANT", "Restaurant", "POOL", "Pool", "GYM", "Gym", "CAR_PARK", "Car park"));
        l.put("HOUSEKEEPING_CHECKLIST", items("BED_MADE", "Bed made", "BATHROOM_CLEANED", "Bathroom cleaned", "TOWELS_REPLACED", "Towels replaced",
                "MINIBAR_CHECKED", "Minibar checked", "FLOOR_CLEANED", "Floor cleaned", "AMENITIES_RESTOCKED", "Amenities restocked"));
        l.put("PAYMENT_METHODS", items("CASH", "Cash", "CARD", "Card", "TRANSFER", "Transfer"));
        LISTS = Collections.unmodifiableMap(l);
    }

    public static boolean isOwnerOnly(String section) {
        return OWNER_ONLY_SECTIONS.contains(section);
    }

    /** The record type of a section name (upper case), or empty for a section the catalogue does not know. */
    public static Optional<Class<? extends SetupSection>> typeOf(String section) {
        return Optional.ofNullable(section == null ? null : SECTIONS.get(section));
    }

    /** The section name of a record type ({@code SetupSection.Payments.class} → PAYMENTS). */
    public static String nameOf(Class<? extends SetupSection> type) {
        for (Map.Entry<String, Class<? extends SetupSection>> e : SECTIONS.entrySet()) {
            if (e.getValue() == type) {
                return e.getKey();
            }
        }
        throw new IllegalArgumentException("Not a Hotel setup section: " + type);
    }

    /** The defaults of one section, or null for a section the catalogue does not know. */
    public static SetupSection defaults(String section) {
        return HotelSetup.DEFAULTS.get(section);
    }

    /** The defaults of one section by its type. */
    public static <T extends SetupSection> T defaults(Class<T> type) {
        return type.cast(HotelSetup.DEFAULTS.get(nameOf(type)));
    }

    /** The keys of a section, in order: the JSON names of its record components. */
    public static List<String> keys(Class<? extends SetupSection> type) {
        return Arrays.stream(type.getRecordComponents()).map(RecordComponent::getName).toList();
    }

    /** How many keys the whole catalogue has. */
    public static int keyCount() {
        return SECTIONS.values().stream().mapToInt(t -> t.getRecordComponents().length).sum();
    }

    /** One value of a section: its key, the Java type it is read as, and the value (null when absent). */
    public record Value(String key, Type type, Object value) {
    }

    /** The keys of a section with their values, in order (absent keys carry a null value). */
    public static List<Value> values(SetupSection section) {
        List<Value> out = new ArrayList<>();
        for (RecordComponent c : section.getClass().getRecordComponents()) {
            out.add(new Value(c.getName(), c.getGenericType(), read(c, section)));
        }
        return out;
    }

    /** The keys a section carries a value for, in order. */
    public static List<Value> present(SetupSection section) {
        return values(section).stream().filter(v -> v.value() != null).toList();
    }

    /** True when the section carries no value at all ({@code {}}). */
    public static boolean isEmpty(SetupSection section) {
        return section == null || present(section).isEmpty();
    }

    /**
     * Builds a section from its keys: {@code value} is asked for each key with the type it must have and answers a value of that
     * type, or null for an absent key.
     */
    public static <T extends SetupSection> T build(Class<T> type, BiFunction<String, Type, Object> value) {
        RecordComponent[] components = type.getRecordComponents();
        Object[] args = new Object[components.length];
        Class<?>[] types = new Class<?>[components.length];
        for (int i = 0; i < components.length; i++) {
            args[i] = value.apply(components[i].getName(), components[i].getGenericType());
            types[i] = components[i].getType();
        }
        try {
            Constructor<T> canonical = type.getDeclaredConstructor(types);
            return canonical.newInstance(args);
        } catch (NoSuchMethodException | InstantiationException | IllegalAccessException | InvocationTargetException e) {
            throw new IllegalStateException("Could not build " + type.getSimpleName(), e);
        }
    }

    /** {@code over}'s values laid over {@code base}: every key {@code over} carries wins, the others keep {@code base}'s. */
    @SuppressWarnings("unchecked")
    public static <T extends SetupSection> T overlay(T base, T over) {
        if (over == null) {
            return base;
        }
        if (base == null) {
            return over;
        }
        Map<String, Object> top = new LinkedHashMap<>();
        values(over).forEach(v -> top.put(v.key(), v.value()));
        Map<String, Object> bottom = new LinkedHashMap<>();
        values(base).forEach(v -> bottom.put(v.key(), v.value()));
        return (T) build(base.getClass(), (key, t) -> top.get(key) != null ? top.get(key) : bottom.get(key));
    }

    /** One section: the defaults with the stored values laid over them. */
    public static <T extends SetupSection> T withDefaults(Class<T> type, T stored) {
        return overlay(defaults(type), stored);
    }

    /** Every catalogue section: the defaults with the stored values (any of them null) laid over them. */
    public static HotelSetup withDefaults(HotelSetup stored) {
        return HotelSetup.of(name -> overlay(HotelSetup.DEFAULTS.get(name), stored == null ? null : stored.get(name)));
    }

    /** True when two values of a key are the same setting (7.5 and 7.50 are the same rate). */
    public static boolean sameValue(Object a, Object b) {
        if (a instanceof BigDecimal x && b instanceof BigDecimal y) {
            return x.compareTo(y) == 0;
        }
        return Objects.equals(a, b);
    }

    /** The whole catalogue as a template: every section's defaults and every list's default entries, numbered from 1. */
    public static SetupTemplateSettings template() {
        Map<String, List<SetupListEntry>> lists = new LinkedHashMap<>();
        LISTS.forEach((name, items) -> {
            List<SetupListEntry> entries = new ArrayList<>();
            for (int i = 0; i < items.size(); i++) {
                entries.add(new SetupListEntry(items.get(i).code(), items.get(i).label(), i + 1));
            }
            lists.put(name, List.copyOf(entries));
        });
        return SetupTemplateSettings.of(HotelSetup.DEFAULTS, Collections.unmodifiableMap(lists));
    }

    private static Object read(RecordComponent c, Object record) {
        try {
            return c.getAccessor().invoke(record);
        } catch (IllegalAccessException | InvocationTargetException e) {
            throw new IllegalStateException("Could not read " + c.getName(), e);
        }
    }

    private static List<ListDefault> items(String... codeLabels) {
        ListDefault[] out = new ListDefault[codeLabels.length / 2];
        for (int i = 0; i < codeLabels.length; i += 2) {
            out[i / 2] = new ListDefault(codeLabels[i], codeLabels[i + 1]);
        }
        return List.of(out);
    }
}
