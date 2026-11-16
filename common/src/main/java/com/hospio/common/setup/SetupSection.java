package com.hospio.common.setup;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;

/**
 * One section of Hotel setup, typed: the keys of each section are the record's components, with the JSON names the web apps
 * send and read ({@code {"checkInTime": "14:00", ...}}). A null component is a key that was not sent or not stored: hotel-config
 * lays its {@link SetupCatalogue} default over it, a reader falls back to its own default.
 *
 * <p>Each record's {@code DEFAULTS} is the catalogue default of that section, in the order the Hotel setup screen shows them.
 */
public sealed interface SetupSection {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Basics(String checkInTime, String checkOutTime, String timezone, String receiptFooter) implements SetupSection {
        public static final Basics DEFAULTS = new Basics("14:00", "12:00", "Africa/Lagos", "");
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Brand(String primaryColour, String shortName, Boolean darkSidebar, String logoUrl) implements SetupSection {
        public static final Brand DEFAULTS = new Brand("#4338ca", "", true, "");
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    record StayRules(Boolean allowOverbooking, BigDecimal lateCheckoutFee, Integer maxStayNights) implements SetupSection {
        public static final StayRules DEFAULTS = new StayRules(false, BigDecimal.ZERO, 30);
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Payments(Boolean allowCash, Boolean allowCard, Boolean allowTransfer, String receipts, BigDecimal depositPct,
                    BigDecimal tillFloat, String bankName, String bankAccount) implements SetupSection {
        public static final Payments DEFAULTS = new Payments(true, true, true, "WHATSAPP", BigDecimal.ZERO, BigDecimal.ZERO, "", "");
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    record TaxesCurrency(String currency, BigDecimal vatPct, BigDecimal serviceChargePct, BigDecimal consumptionTaxPct,
                         Boolean pricesIncludeTax) implements SetupSection {
        public static final TaxesCurrency DEFAULTS = new TaxesCurrency("NGN", new BigDecimal("7.5"), BigDecimal.ZERO, BigDecimal.ZERO, true);
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    record ApprovalLimits(BigDecimal managerExpenseLimit, BigDecimal ownerExpenseLimit, BigDecimal discountPct, Boolean refundsNeedOwner,
                          Boolean rateChangesNeedOwner, BigDecimal rateChangeOwnerPct) implements SetupSection {
        public static final ApprovalLimits DEFAULTS = new ApprovalLimits(BigDecimal.valueOf(200000), BigDecimal.ZERO, BigDecimal.TEN, false,
                true, BigDecimal.ZERO);
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Housekeeping(Boolean inspectDepartures, Boolean minibarToFolio, Integer lostFoundHoldDays) implements SetupSection {
        public static final Housekeeping DEFAULTS = new Housekeeping(true, true, 90);
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Outlets(Boolean roomCharge, Boolean tabs, BigDecimal tabLimit) implements SetupSection {
        public static final Outlets DEFAULTS = new Outlets(true, true, BigDecimal.valueOf(50000));
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Messaging(String whatsappNumber, Boolean sendConfirmation, Boolean arrivalMessage, Boolean sendReceipts) implements SetupSection {
        public static final Messaging DEFAULTS = new Messaging("", true, true, true);
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Staff(Integer lateMinutes, Boolean notifyEveryClockIn) implements SetupSection {
        public static final Staff DEFAULTS = new Staff(15, false);
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    record NightAudit(String closeTime, Boolean tillsClosedFirst, Boolean arrivalsResolvedFirst) implements SetupSection {
        public static final NightAudit DEFAULTS = new NightAudit("23:00", true, true);
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Pos(String provider, Boolean offlineCash, String receiptChannel) implements SetupSection {
        public static final Pos DEFAULTS = new Pos("logging", true, "PRINT");
    }
}
