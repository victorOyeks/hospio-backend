package com.hospio.common.setup;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.function.Function;

/**
 * Every Hotel setup section, keyed on the wire by the section name ({@code {"BASICS": {...}, "BRAND": {...}, ...}}): what
 * {@code GET /hotel-config/setup} answers and the {@code sections} of a setup template. A null section is one that was not
 * sent (a template that leaves it alone) or has nothing stored.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record HotelSetup(
        @JsonProperty(SetupCatalogue.BASICS) @JsonAlias("basics") SetupSection.Basics basics,
        @JsonProperty(SetupCatalogue.BRAND) @JsonAlias("brand") SetupSection.Brand brand,
        @JsonProperty(SetupCatalogue.STAY_RULES) @JsonAlias("stay_rules") SetupSection.StayRules stayRules,
        @JsonProperty(SetupCatalogue.PAYMENTS) @JsonAlias("payments") SetupSection.Payments payments,
        @JsonProperty(SetupCatalogue.TAXES_CURRENCY) @JsonAlias("taxes_currency") SetupSection.TaxesCurrency taxesCurrency,
        @JsonProperty(SetupCatalogue.APPROVAL_LIMITS) @JsonAlias("approval_limits") SetupSection.ApprovalLimits approvalLimits,
        @JsonProperty(SetupCatalogue.HOUSEKEEPING) @JsonAlias("housekeeping") SetupSection.Housekeeping housekeeping,
        @JsonProperty(SetupCatalogue.OUTLETS) @JsonAlias("outlets") SetupSection.Outlets outlets,
        @JsonProperty(SetupCatalogue.MESSAGING) @JsonAlias("messaging") SetupSection.Messaging messaging,
        @JsonProperty(SetupCatalogue.STAFF) @JsonAlias("staff") SetupSection.Staff staff,
        @JsonProperty(SetupCatalogue.NIGHT_AUDIT) @JsonAlias("night_audit") SetupSection.NightAudit nightAudit,
        @JsonProperty(SetupCatalogue.POS) @JsonAlias("pos") SetupSection.Pos pos) {

    /** Every section the catalogue knows, with its defaults. */
    public static final HotelSetup DEFAULTS = new HotelSetup(SetupSection.Basics.DEFAULTS, SetupSection.Brand.DEFAULTS,
            SetupSection.StayRules.DEFAULTS, SetupSection.Payments.DEFAULTS, SetupSection.TaxesCurrency.DEFAULTS,
            SetupSection.ApprovalLimits.DEFAULTS, SetupSection.Housekeeping.DEFAULTS, SetupSection.Outlets.DEFAULTS,
            SetupSection.Messaging.DEFAULTS, SetupSection.Staff.DEFAULTS, SetupSection.NightAudit.DEFAULTS, SetupSection.Pos.DEFAULTS);

    /** The section stored under {@code name} (a {@link SetupCatalogue} section name), or null when absent or unknown. */
    public SetupSection get(String name) {
        return switch (name) {
            case SetupCatalogue.BASICS -> basics;
            case SetupCatalogue.BRAND -> brand;
            case SetupCatalogue.STAY_RULES -> stayRules;
            case SetupCatalogue.PAYMENTS -> payments;
            case SetupCatalogue.TAXES_CURRENCY -> taxesCurrency;
            case SetupCatalogue.APPROVAL_LIMITS -> approvalLimits;
            case SetupCatalogue.HOUSEKEEPING -> housekeeping;
            case SetupCatalogue.OUTLETS -> outlets;
            case SetupCatalogue.MESSAGING -> messaging;
            case SetupCatalogue.STAFF -> staff;
            case SetupCatalogue.NIGHT_AUDIT -> nightAudit;
            case SetupCatalogue.POS -> pos;
            default -> null;
        };
    }

    /** Builds a setup by asking for each catalogue section by name (null for a section left out). */
    public static HotelSetup of(Function<String, SetupSection> section) {
        return new HotelSetup((SetupSection.Basics) section.apply(SetupCatalogue.BASICS), (SetupSection.Brand) section.apply(SetupCatalogue.BRAND),
                (SetupSection.StayRules) section.apply(SetupCatalogue.STAY_RULES), (SetupSection.Payments) section.apply(SetupCatalogue.PAYMENTS),
                (SetupSection.TaxesCurrency) section.apply(SetupCatalogue.TAXES_CURRENCY),
                (SetupSection.ApprovalLimits) section.apply(SetupCatalogue.APPROVAL_LIMITS),
                (SetupSection.Housekeeping) section.apply(SetupCatalogue.HOUSEKEEPING), (SetupSection.Outlets) section.apply(SetupCatalogue.OUTLETS),
                (SetupSection.Messaging) section.apply(SetupCatalogue.MESSAGING), (SetupSection.Staff) section.apply(SetupCatalogue.STAFF),
                (SetupSection.NightAudit) section.apply(SetupCatalogue.NIGHT_AUDIT), (SetupSection.Pos) section.apply(SetupCatalogue.POS));
    }
}
