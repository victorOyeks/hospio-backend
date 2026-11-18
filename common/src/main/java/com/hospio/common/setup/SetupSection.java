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
    /** {@code openingTime}/{@code closingTime}: when a lounge, bar or restaurant trades ("" = not set); a hotel's desk never closes. */
    record Basics(String checkInTime, String checkOutTime, String timezone, String receiptFooter, String openingTime, String closingTime)
            implements SetupSection {
        public static final Basics DEFAULTS = new Basics("14:00", "12:00", "Africa/Lagos", "", "", "");

        public Basics(String checkInTime, String checkOutTime, String timezone, String receiptFooter) {
            this(checkInTime, checkOutTime, timezone, receiptFooter, null, null);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Brand(String primaryColour, String shortName, Boolean darkSidebar, String logoUrl) implements SetupSection {
        public static final Brand DEFAULTS = new Brand("#4c091e", "", true, "");
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    record StayRules(Boolean allowOverbooking, BigDecimal lateCheckoutFee, Integer maxStayNights) implements SetupSection {
        public static final StayRules DEFAULTS = new StayRules(false, BigDecimal.ZERO, 30);
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    /**
     * {@code guestPaysPlatformFee}: the owner adds Hospio360's fee to guests' bills as "Service charge (x%)" instead of absorbing it
     * (docs/plan-device-packages.md). {@code platformFeePct} is the business's fee rate, filled in by hotel-config whenever the section
     * is read (never stored), so the charge always matches the fee.
     */
    record Payments(Boolean allowCash, Boolean allowCard, Boolean allowTransfer, String receipts, BigDecimal depositPct,
                    BigDecimal tillFloat, String bankName, String bankAccount, Boolean guestPaysPlatformFee, BigDecimal platformFeePct)
            implements SetupSection {
        public static final Payments DEFAULTS = new Payments(true, true, true, "WHATSAPP", BigDecimal.ZERO, BigDecimal.ZERO, "", "", false, BigDecimal.ZERO);

        public Payments(Boolean allowCash, Boolean allowCard, Boolean allowTransfer, String receipts, BigDecimal depositPct, BigDecimal tillFloat,
                        String bankName, String bankAccount) {
            this(allowCash, allowCard, allowTransfer, receipts, depositPct, tillFloat, bankName, bankAccount, null, null);
        }

        public Payments withGuestPays(Boolean guestPays) {
            return new Payments(allowCash, allowCard, allowTransfer, receipts, depositPct, tillFloat, bankName, bankAccount, guestPays, platformFeePct);
        }

        public Payments withPlatformFeePct(BigDecimal pct) {
            return new Payments(allowCash, allowCard, allowTransfer, receipts, depositPct, tillFloat, bankName, bankAccount, guestPaysPlatformFee, pct);
        }
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
    /**
     * {@code tabMaxHours}: a tab open longer is flagged to the manager (dashboards, "Needs your action"). {@code tabNameRequired}: a
     * tab needs the guest's name or a table. {@code waitersTakePayment}: waiters may take payment for their own orders (off: the
     * cashier does). {@code waiterCash}: a waiter may take cash, which goes into the cashier's open till (off: card and transfer only).
     * {@code discountRoles}: the outlet roles (comma separated: CASHIER, BAR, KITCHEN, WAITER) that give a discount on a sale without
     * asking, up to APPROVAL_LIMITS.discountPct; anything above, a complimentary item, or anyone else asks a manager.
     * {@code recordTips}: a tip can be added when a sale is paid (off by default: nothing about tips shows anywhere).
     */
    record Outlets(Boolean roomCharge, Boolean tabs, BigDecimal tabLimit, Integer tabMaxHours, Boolean tabNameRequired,
                   Boolean waitersTakePayment, Boolean waiterCash, String discountRoles, Boolean recordTips) implements SetupSection {
        /** Who gives a discount on an outlet sale without asking, up to APPROVAL_LIMITS.discountPct (managers and owners: any). */
        public static final String DEFAULT_DISCOUNT_ROLES = "CASHIER,BAR";
        public static final Outlets DEFAULTS = new Outlets(true, true, BigDecimal.valueOf(50000), 4, false, false, false, DEFAULT_DISCOUNT_ROLES, false);

        public Outlets(Boolean roomCharge, Boolean tabs, BigDecimal tabLimit) {
            this(roomCharge, tabs, tabLimit, null, null, null, null, null, null);
        }

        /** Before discounts and tips (venue type, phase 4): both left as stored or default. */
        public Outlets(Boolean roomCharge, Boolean tabs, BigDecimal tabLimit, Integer tabMaxHours, Boolean tabNameRequired,
                       Boolean waitersTakePayment, Boolean waiterCash) {
            this(roomCharge, tabs, tabLimit, tabMaxHours, tabNameRequired, waitersTakePayment, waiterCash, null, null);
        }

        public Outlets withRoomCharge(Boolean value) {
            return new Outlets(value, tabs, tabLimit, tabMaxHours, tabNameRequired, waitersTakePayment, waiterCash, discountRoles, recordTips);
        }
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
