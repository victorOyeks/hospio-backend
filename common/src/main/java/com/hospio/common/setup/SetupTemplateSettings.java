package com.hospio.common.setup;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;

/**
 * A setup template's settings: {@code {"sections": {SECTION: {key: value}}, "lists": {LIST: [{code, label, sortOrder}]}}}, or the
 * older flat {@code {SECTION: {key: value}}} (the flat sections are read only when neither {@code sections} nor {@code lists} is
 * there). tenant-service keeps it as JSON text; hotel-config applies it to a hotel.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record SetupTemplateSettings(
        HotelSetup sections,
        Map<String, List<SetupListEntry>> lists,
        @JsonProperty(SetupCatalogue.BASICS) SetupSection.Basics basics,
        @JsonProperty(SetupCatalogue.BRAND) SetupSection.Brand brand,
        @JsonProperty(SetupCatalogue.STAY_RULES) SetupSection.StayRules stayRules,
        @JsonProperty(SetupCatalogue.PAYMENTS) SetupSection.Payments payments,
        @JsonProperty(SetupCatalogue.TAXES_CURRENCY) SetupSection.TaxesCurrency taxesCurrency,
        @JsonProperty(SetupCatalogue.APPROVAL_LIMITS) SetupSection.ApprovalLimits approvalLimits,
        @JsonProperty(SetupCatalogue.HOUSEKEEPING) SetupSection.Housekeeping housekeeping,
        @JsonProperty(SetupCatalogue.OUTLETS) SetupSection.Outlets outlets,
        @JsonProperty(SetupCatalogue.MESSAGING) SetupSection.Messaging messaging,
        @JsonProperty(SetupCatalogue.STAFF) SetupSection.Staff staff,
        @JsonProperty(SetupCatalogue.NIGHT_AUDIT) SetupSection.NightAudit nightAudit,
        @JsonProperty(SetupCatalogue.POS) SetupSection.Pos pos) {

    /** The nested form, as tenant-service stores it. */
    public static SetupTemplateSettings of(HotelSetup sections, Map<String, List<SetupListEntry>> lists) {
        return new SetupTemplateSettings(sections, lists, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    /** The sections to apply: {@code sections} in the nested form (none when only lists are given), the top-level ones in the flat form. */
    @JsonIgnore
    public HotelSetup sectionsToApply() {
        if (sections != null || lists != null) {
            return sections;
        }
        return new HotelSetup(basics, brand, stayRules, payments, taxesCurrency, approvalLimits, housekeeping, outlets, messaging, staff,
                nightAudit, pos);
    }

    /** The lists to apply (never null). */
    @JsonIgnore
    public Map<String, List<SetupListEntry>> listsToApply() {
        return lists == null ? Map.of() : lists;
    }
}
