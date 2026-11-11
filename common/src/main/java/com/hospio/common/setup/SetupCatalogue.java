package com.hospio.common.setup;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The Hotel setup contract with the web apps: every section, its keys and their defaults, and the
 * default entries of each list (docs/onboarding.md, hotel-config-service). hotel-config answers
 * {@code GET /setup} by laying a hotel's stored values over these defaults, so a brand-new hotel already
 * answers fully; tenant-service seeds the "Standard hotel" template from {@link #templateJson()}. Keys a
 * hotel stores that are not listed here are kept as they are.
 */
public final class SetupCatalogue {

    private SetupCatalogue() {
    }

    private static final JsonNodeFactory J = JsonNodeFactory.instance;

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

    /** Section → key → default, in the order the Hotel setup screen shows them. */
    public static final Map<String, Map<String, JsonNode>> SECTIONS;

    public record ListDefault(String code, String label) {
    }

    /** List name → default entries, in display order. */
    public static final Map<String, List<ListDefault>> LISTS;

    static {
        Map<String, Map<String, JsonNode>> s = new LinkedHashMap<>();
        s.put(BASICS, section("checkInTime", t("14:00"), "checkOutTime", t("12:00"), "timezone", t("Africa/Lagos"), "receiptFooter", t("")));
        s.put(BRAND, section("primaryColour", t("#4c091e"), "shortName", t(""), "darkSidebar", b(true), "logoUrl", t("")));
        s.put(STAY_RULES, section("allowOverbooking", b(false), "lateCheckoutFee", n(0), "maxStayNights", n(30)));
        s.put(PAYMENTS, section("allowCash", b(true), "allowCard", b(true), "allowTransfer", b(true), "receipts", t("WHATSAPP"),
                "depositPct", n(0), "tillFloat", n(0), "bankName", t(""), "bankAccount", t("")));
        s.put(TAXES_CURRENCY, section("currency", t("NGN"), "vatPct", d("7.5"), "serviceChargePct", n(0), "consumptionTaxPct", n(0),
                "pricesIncludeTax", b(true)));
        s.put(APPROVAL_LIMITS, section("managerExpenseLimit", n(200000), "ownerExpenseLimit", n(0), "discountPct", n(10),
                "refundsNeedOwner", b(false), "rateChangesNeedOwner", b(true), "rateChangeOwnerPct", n(0)));
        s.put(HOUSEKEEPING, section("inspectDepartures", b(true), "minibarToFolio", b(true), "lostFoundHoldDays", n(90)));
        s.put(OUTLETS, section("roomCharge", b(true), "tabs", b(true), "tabLimit", n(50000)));
        s.put(MESSAGING, section("whatsappNumber", t(""), "sendConfirmation", b(true), "arrivalMessage", b(true), "sendReceipts", b(true)));
        s.put(STAFF, section("lateMinutes", n(15)));
        s.put(NIGHT_AUDIT, section("closeTime", t("23:00"), "tillsClosedFirst", b(true), "arrivalsResolvedFirst", b(true)));
        s.put(POS, section("provider", t("logging"), "offlineCash", b(true), "receiptChannel", t("PRINT")));
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

    /** The defaults of one section (empty for a section the catalogue does not know). */
    public static Map<String, JsonNode> defaults(String section) {
        return SECTIONS.getOrDefault(section, Map.of());
    }

    /** One section: the defaults with the stored values laid over them; stored keys the catalogue lacks are kept. */
    public static Map<String, JsonNode> mergeSection(String section, Map<String, JsonNode> stored) {
        Map<String, JsonNode> out = new LinkedHashMap<>(defaults(section));
        if (stored != null) {
            out.putAll(stored);
        }
        return out;
    }

    /** Every catalogue section merged over the stored values, then any stored section the catalogue lacks. */
    public static Map<String, Map<String, JsonNode>> merge(Map<String, Map<String, JsonNode>> stored) {
        Map<String, Map<String, JsonNode>> out = new LinkedHashMap<>();
        for (String section : SECTIONS.keySet()) {
            out.put(section, mergeSection(section, stored == null ? null : stored.get(section)));
        }
        if (stored != null) {
            stored.forEach((section, values) -> out.computeIfAbsent(section, k -> new LinkedHashMap<>(values)));
        }
        return out;
    }

    /** The whole catalogue as a template: {"sections": {SECTION: {key: value}}, "lists": {LIST: [{code, label, sortOrder}]}}. */
    public static ObjectNode templateJson() {
        ObjectNode root = J.objectNode();
        ObjectNode sections = root.putObject("sections");
        SECTIONS.forEach((name, values) -> {
            ObjectNode node = sections.putObject(name);
            values.forEach(node::set);
        });
        ObjectNode lists = root.putObject("lists");
        LISTS.forEach((name, items) -> {
            ArrayNode arr = lists.putArray(name);
            for (int i = 0; i < items.size(); i++) {
                ObjectNode item = arr.addObject();
                item.put("code", items.get(i).code());
                item.put("label", items.get(i).label());
                item.put("sortOrder", i + 1);
            }
        });
        return root;
    }

    private static Map<String, JsonNode> section(Object... keyValues) {
        Map<String, JsonNode> m = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            m.put((String) keyValues[i], (JsonNode) keyValues[i + 1]);
        }
        return Collections.unmodifiableMap(m);
    }

    private static List<ListDefault> items(String... codeLabels) {
        ListDefault[] out = new ListDefault[codeLabels.length / 2];
        for (int i = 0; i < codeLabels.length; i += 2) {
            out[i / 2] = new ListDefault(codeLabels[i], codeLabels[i + 1]);
        }
        return List.of(out);
    }

    private static JsonNode t(String v) {
        return J.textNode(v);
    }

    private static JsonNode b(boolean v) {
        return J.booleanNode(v);
    }

    private static JsonNode n(long v) {
        return J.numberNode(v);
    }

    private static JsonNode d(String v) {
        return J.numberNode(new BigDecimal(v));
    }
}
