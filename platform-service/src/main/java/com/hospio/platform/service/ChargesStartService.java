package com.hospio.platform.service;

import com.hospio.common.delivery.Delivery;
import com.hospio.common.delivery.DeliveryService;
import com.hospio.common.enums.Role;
import com.hospio.common.exception.BadRequestException;
import com.hospio.common.exception.ForbiddenException;
import com.hospio.common.exception.ResourceNotFoundException;
import com.hospio.common.kafka.EventPublisher;
import com.hospio.common.kafka.KafkaTopics;
import com.hospio.common.kafka.events.NoticeEvent;
import com.hospio.common.security.CurrentUser;
import com.hospio.common.security.HospioUserDetails;
import com.hospio.common.tenancy.Tenant;
import com.hospio.common.tenancy.TenantContext;
import com.hospio.common.util.Money;
import com.hospio.platform.client.IdentityClient;
import com.hospio.platform.client.TenantClient;
import com.hospio.platform.dto.PlatformRequests;
import com.hospio.platform.entity.BillingStart;
import com.hospio.platform.entity.BillingStartChange;
import com.hospio.platform.entity.DailyFee;
import com.hospio.platform.repository.BillingStartRepository;
import com.hospio.platform.repository.DailyFeeRepository;
import com.hospio.platform.repository.BillingStartChangeRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

/**
 * "Charges start on" (docs/plan-monthly-invoices.md): the day a business's platform fee starts. No date = free: each day's fee is worked
 * out for visibility as FREE (fee 0, the would-be fee in {@code waivedFee}) and never invoiced, so there are no platform-fee invoices,
 * reminders, restrictions or suspension candidates, and the referring marketer earns nothing (nothing is paid). Days on or after the date
 * are ACCRUED as usual. Devices (instalments, data plan, the from-sales share) are billed either way.
 *
 * <p>Money admins (SUPER_ADMIN, FINANCE_ADMIN) set, move or clear the date with a reason (Changes by Hospio360, area "Billing start"). A
 * change works out again only the days not on an invoice yet: an issued invoice is never touched. Making FREE days billable (an earlier
 * date) has to be confirmed. Money admins are reminded (bell and email) 7 days before a business's date.
 */
@Slf4j
@Service
public class ChargesStartService {

    public static final ZoneId LAGOS = ZoneId.of("Africa/Lagos");
    public static final String AREA = "Billing start";
    public static final int REMIND_DAYS = 7;
    static final String NOTICE_KIND = "CHARGES_START";
    /** Days a change may work out again: not on an invoice, not from the debit trial. */
    static final List<String> REDOABLE = List.of(DailyFee.ACCRUED, DailyFee.FREE, DailyFee.NO_FEE, DailyFee.SHADOW, DailyFee.SCHEDULED);
    static final DateTimeFormatter TAG = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.UK);
    static final DateTimeFormatter LONG = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.UK);
    private static final Pattern DATE_IN_JSON = Pattern.compile("\"chargesStartOn\"\\s*:\\s*\"(\\d{4}-\\d{2}-\\d{2})\"");

    private final BillingStartRepository starts;
    private final DailyFeeRepository fees;
    private final BillingStartChangeRepository changeRows;
    private final ChangeService changes;
    private final TenantClient tenant;
    private final FeeService feeRules;
    private final IdentityClient identity;
    private final DeliveryService deliveries;
    private final ObjectProvider<EventPublisher> events;
    private final TransactionTemplate tx;
    private final boolean qaOnly;
    private final Set<String> qaBusinessIds;

    public ChargesStartService(BillingStartRepository starts, DailyFeeRepository fees, BillingStartChangeRepository changeRows, ChangeService changes,
                               TenantClient tenant, FeeService feeRules, IdentityClient identity, DeliveryService deliveries,
                               ObjectProvider<EventPublisher> events, TransactionTemplate tx,
                               @Value("${hospio.billing.qa-only:false}") boolean qaOnly,
                               @Value("${hospio.billing.qa-business-ids:}") String qaBusinessIds) {
        this.starts = starts;
        this.fees = fees;
        this.changeRows = changeRows;
        this.changes = changes;
        this.tenant = tenant;
        this.feeRules = feeRules;
        this.identity = identity;
        this.deliveries = deliveries;
        this.events = events;
        this.tx = tx;
        this.qaOnly = qaOnly;
        this.qaBusinessIds = Arrays.stream(qaBusinessIds == null ? new String[0] : qaBusinessIds.split(","))
                .map(String::trim).filter(StringUtils::hasText).collect(Collectors.toSet());
    }

    // ---- the rule ----------------------------------------------------------------------------------------------

    /** A day is free when the business has no charges start date or the day is before it. */
    public static boolean free(LocalDate start, LocalDate day) {
        return start == null || day.isBefore(start);
    }

    /** FREE (no date), STARTS (a date after today) or BILLING (the date is today or past). */
    public static String state(LocalDate start, LocalDate today) {
        return start == null ? "FREE" : start.isAfter(today) ? "STARTS" : "BILLING";
    }

    public static String label(LocalDate start) {
        return start == null ? "Free" : "Billing from " + TAG.format(start);
    }

    /**
     * Works a day not on an invoice out again for {@code start}, from what it already holds (sales, rate, device share): its platform fee
     * ({@code fee + waivedFee}) is charged on or after the start and waived before it. Returns whether anything changed.
     */
    public static boolean apply(DailyFee d, LocalDate start) {
        BigDecimal base = Money.of(d.getFee().add(nz(d.getWaivedFee())));
        boolean free = free(start, d.getFeeDate());
        BigDecimal fee = free ? Money.of(BigDecimal.ZERO) : base;
        BigDecimal waived = free ? base : Money.of(BigDecimal.ZERO);
        BigDecimal due = Money.of(fee.add(nz(d.getCarriedIn())).add(nz(d.getDeviceFee())).add(nz(d.getDeviceCarriedIn())));
        String status = free && base.signum() > 0 ? DailyFee.FREE : due.signum() > 0 ? DailyFee.ACCRUED : DailyFee.NO_FEE;
        boolean changed = d.getFee().compareTo(fee) != 0 || nz(d.getWaivedFee()).compareTo(waived) != 0 || d.getDue().compareTo(due) != 0
                || !status.equals(d.getStatus());
        d.setFee(fee);
        d.setWaivedFee(waived);
        d.setDue(due);
        d.setStatus(status);
        return changed;
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    /** The business's start date (null = free). Read as the system: billing jobs and the hotel app both ask. */
    public LocalDate startOn(String businessId) {
        if (businessId == null) {
            return null;
        }
        return asSystem(() -> starts.findByBusinessId(businessId).map(BillingStart::getChargesStartOn).orElse(null));
    }

    // ---- views -------------------------------------------------------------------------------------------------

    public record Row(String businessId, LocalDate chargesStartOn, String state, String label) {
    }

    public record Rows(List<Row> rows) {
    }

    public record HistoryItem(String id, Instant createdAt, String madeByName, String description, String reason, LocalDate before, LocalDate after) {
    }

    public record View(String businessId, LocalDate chargesStartOn, String state, String label, BigDecimal ratePct, boolean canEdit, String setByName,
                       Instant setAt, String reason, List<HistoryItem> history) {
    }

    public record Shift(int days, BigDecimal fee) {
    }

    public record Preview(LocalDate current, LocalDate proposed, boolean earlier, Shift toBill, Shift toFree, String message) {
    }

    /** What the hotel app shows on Billing & invoices. */
    public record OwnerView(String state, LocalDate chargesStartOn, BigDecimal ratePct, String title, String message) {
    }

    /** For messaging-service: whether guest messages are free today (sent at NGN 0 before the charges start). */
    public record InternalView(String businessId, LocalDate chargesStartOn, boolean freeToday) {
    }

    /** Every business with a record (the admin lists' "Free" / "Billing from …" tags); a business not listed is free. */
    public Rows rows() {
        LocalDate today = LocalDate.now(LAGOS);
        return new Rows(asSystem(() -> starts.findAll()).stream()
                .map(s -> new Row(s.getBusinessId(), s.getChargesStartOn(), state(s.getChargesStartOn(), today), label(s.getChargesStartOn()))).toList());
    }

    public View view(String businessId) {
        HospioUserDetails me = CurrentUser.require();
        BillingStart s = asSystem(() -> starts.findByBusinessId(businessId).orElse(null));
        LocalDate start = s == null ? null : s.getChargesStartOn();
        List<HistoryItem> history = asSystem(() -> changeRows.findByBusinessIdOrderByCreatedAtDesc(businessId)).stream()
                .map(c -> new HistoryItem(c.getId(), c.getCreatedAt(), c.getMadeByName(), c.getDescription(), c.getReason(), c.getBeforeOn(),
                        c.getAfterOn())).toList();
        BigDecimal rate = null;
        try {
            rate = feeRules.dailySalesPct(businessId);
        } catch (RuntimeException e) {
            log.debug("Rate for {} not read: {}", businessId, e.getMessage());
        }
        return new View(businessId, start, state(start, LocalDate.now(LAGOS)), label(start), rate == null ? feeRules.defaultSalesPct() : rate,
                money(me), s == null ? null : s.getSetByName(), s == null ? null : s.getUpdatedAt(), s == null ? null : s.getReason(), history);
    }

    static LocalDate dateIn(String json) {
        if (json == null) {
            return null;
        }
        Matcher m = DATE_IN_JSON.matcher(json);
        return m.find() ? LocalDate.parse(m.group(1)) : null;
    }

    public OwnerView forOwner() {
        HospioUserDetails me = CurrentUser.require();
        if (me.getBusinessId() == null) {
            throw new BadRequestException("Only a business's own people see its charges");
        }
        LocalDate start = startOn(me.getBusinessId());
        BigDecimal rate = null;
        try {
            rate = asSystem(() -> feeRules.dailySalesPct(me.getBusinessId()));
        } catch (RuntimeException e) {
            log.debug("Rate for {} not read: {}", me.getBusinessId(), e.getMessage());
        }
        BigDecimal pct = rate == null ? feeRules.defaultSalesPct() : rate;
        return ownerView(start, pct, LocalDate.now(LAGOS));
    }

    static OwnerView ownerView(LocalDate start, BigDecimal pct, LocalDate today) {
        String state = state(start, today);
        String rate = pct.stripTrailingZeros().toPlainString() + "% of daily sales, invoiced monthly";
        return switch (state) {
            case "FREE" -> new OwnerView(state, null, pct, "Free: no charges until Hospio360 tells you",
                    "Your daily Hospio360 fee shows under Hospio fees as free (NGN 0). Guest messages you switch on are sent at NGN 0. Devices and internet are"
                            + " still billed as agreed.");
            case "STARTS" -> new OwnerView(state, start, pct, "Charges start on " + LONG.format(start) + " — " + rate,
                    "Until then it is free: each day shows under Hospio fees at NGN 0. Your first invoice comes on the 1st after your first billed month."
                            + " Devices and internet are billed as agreed.");
            default -> new OwnerView(state, start, pct, "Charges started on " + LONG.format(start) + " — " + rate,
                    "Each day's fee is billed on the monthly invoice, issued on the 1st and due 7 days later.");
        };
    }

    public InternalView internal(String businessId) {
        LocalDate start = startOn(businessId);
        return new InternalView(businessId, start, free(start, LocalDate.now(LAGOS)));
    }

    // ---- changing the date ---------------------------------------------------------------------------------------

    /** What moving the date would do to the days not on an invoice yet (nothing is saved). */
    public Preview preview(String businessId, LocalDate proposed) {
        requireMoney();
        LocalDate current = startOn(businessId);
        List<DailyFee> open = asSystem(() -> fees.findByBusinessIdAndMonthlyInvoiceIdIsNullAndStatusInOrderByFeeDateAsc(businessId, REDOABLE));
        return preview(current, proposed, open);
    }

    static Preview preview(LocalDate current, LocalDate proposed, List<DailyFee> open) {
        int billDays = 0;
        int freeDays = 0;
        BigDecimal billFee = BigDecimal.ZERO;
        BigDecimal freeFee = BigDecimal.ZERO;
        for (DailyFee d : open) {
            BigDecimal base = d.getFee().add(nz(d.getWaivedFee()));
            if (base.signum() <= 0) {
                continue;
            }
            boolean wasFree = DailyFee.FREE.equals(d.getStatus());
            boolean willBeFree = free(proposed, d.getFeeDate());
            if (wasFree && !willBeFree) {
                billDays++;
                billFee = billFee.add(base);
            } else if (!wasFree && willBeFree) {
                freeDays++;
                freeFee = freeFee.add(base);
            }
        }
        Shift toBill = new Shift(billDays, Money.of(billFee));
        Shift toFree = new Shift(freeDays, Money.of(freeFee));
        List<String> parts = new ArrayList<>();
        if (billDays > 0) {
            parts.add(billDays + " free day" + (billDays == 1 ? "" : "s") + " not on an invoice yet become billable (NGN " + MonthlyInvoiceService.naira(toBill.fee())
                    + "): they go on the next invoice");
        }
        if (freeDays > 0) {
            parts.add(freeDays + " billed day" + (freeDays == 1 ? "" : "s") + " not on an invoice yet become free (NGN " + MonthlyInvoiceService.naira(toFree.fee()) + ")");
        }
        String message = parts.isEmpty() ? "No day worked out so far changes. Invoices already issued are never changed."
                : String.join("; ", parts) + ". Invoices already issued are never changed.";
        return new Preview(current, proposed, billDays > 0, toBill, toFree, message);
    }

    public record SetRequest(LocalDate chargesStartOn, String reason, Boolean confirmEarlier) {
    }

    /** Sets, moves or clears the date (money roles), with a reason, and works the days not on an invoice out again. */
    public View set(String businessId, SetRequest r) {
        HospioUserDetails me = requireMoney();
        if (r == null || !StringUtils.hasText(r.reason()) || r.reason().trim().length() < 8) {
            throw new BadRequestException("Give a reason (at least 8 characters)");
        }
        String reason = r.reason().trim();
        if (reason.length() > 500) {
            throw new BadRequestException("Keep the reason under 500 characters");
        }
        LocalDate proposed = r.chargesStartOn();
        TenantClient.Business b = asSystem(() -> tenant.business(businessId)).orElseThrow(() -> new ResourceNotFoundException("Business not found"));
        BillingStart existing = asSystem(() -> starts.findByBusinessId(businessId).orElse(null));
        LocalDate current = existing == null ? null : existing.getChargesStartOn();
        if (Objects.equals(current, proposed)) {
            throw new BadRequestException(proposed == null ? b.name() + " is already free (no charges start date)"
                    : "Charges already start on " + LONG.format(proposed) + " for " + b.name());
        }
        int[] redone = {0};
        String[] said = {null};
        asSystem(() -> tx.execute(st -> {
            fees.lock("monthly-invoice:" + businessId);
            List<DailyFee> open = fees.findByBusinessIdAndMonthlyInvoiceIdIsNullAndStatusInOrderByFeeDateAsc(businessId, REDOABLE);
            Preview p = preview(current, proposed, open);
            if (p.earlier() && !Boolean.TRUE.equals(r.confirmEarlier())) {
                throw new BadRequestException(p.message() + " Confirm to go ahead.");
            }
            BillingStart s = existing != null ? existing : BillingStart.builder().businessId(businessId).build();
            s.setBusinessName(b.name());
            s.setChargesStartOn(proposed);
            s.setSetBy(me.getUserId());
            s.setSetByName(me.getFullName());
            s.setReason(reason);
            starts.save(s);
            for (DailyFee d : open) {
                if (apply(d, proposed)) {
                    fees.save(d);
                    redone[0]++;
                }
            }
            String description = proposed == null ? "Charges start date cleared: free from the next day not on an invoice"
                    + (current == null ? "" : " (was " + LONG.format(current) + ")")
                    : "Charges start on " + LONG.format(proposed) + (current == null ? " (was free)" : " (was " + LONG.format(current) + ")");
            said[0] = description;
            changeRows.save(BillingStartChange.builder().businessId(businessId).beforeOn(current).afterOn(proposed).description(description)
                    .reason(reason).madeBy(me.getUserId()).madeByName("Hospio360 · " + me.getFullName()).daysRedone(redone[0]).build());
            return null;
        }));
        // After the commit: the audit record re-binds the connection to the business (its CHANGE_BY_PLATFORM event), which must not happen
        // before this transaction's platform-only rows are flushed. The permanent history row above is already saved.
        try {
            asSystem(() -> changes.recordAs(new PlatformRequests.PlatformChangeRequest(businessId, null, AREA, said[0], reason, json(current), json(proposed)),
                    me.getUserId(), me.getFullName()));
        } catch (RuntimeException e) {
            log.warn("Charges start change for {} saved, but not recorded in Changes by Hospio360: {}", businessId, e.getMessage());
        }
        log.info("Charges start for {} ({}): {} → {} by {}; {} day(s) worked out again", b.name(), businessId, current, proposed, me.getFullName(), redone[0]);
        return view(businessId);
    }

    static String json(LocalDate d) {
        return "{\"chargesStartOn\":" + (d == null ? "null" : "\"" + d + "\"") + "}";
    }

    private static HospioUserDetails requireMoney() {
        HospioUserDetails me = CurrentUser.require();
        if (!money(me)) {
            throw new ForbiddenException("Only a Super admin or Finance admin can change when charges start");
        }
        return me;
    }

    private static boolean money(HospioUserDetails me) {
        return me.isPlatform() && (me.has(Role.SUPER_ADMIN) || me.has(Role.FINANCE_ADMIN));
    }

    // ---- the 7-day reminder ----------------------------------------------------------------------------------------

    public record ReminderRun(LocalDate today, LocalDate startsOn, int businesses, int reminded, List<String> names) {
    }

    /**
     * Bell and email to every Super admin and Finance admin for each business whose charges start {@value #REMIND_DAYS} days from
     * {@code today}; once per business and date (a new date reminds again). On staging (BILLING_QA_ONLY) only QA businesses.
     */
    public ReminderRun remind(LocalDate today) {
        LocalDate day = today.plusDays(REMIND_DAYS);
        List<BillingStart> due = asSystem(() -> starts.findByChargesStartOn(day)).stream()
                .filter(s -> !day.equals(s.getRemindedFor()) && billable(s)).toList();
        List<String> names = new ArrayList<>();
        if (due.isEmpty()) {
            return new ReminderRun(today, day, 0, 0, names);
        }
        Map<String, IdentityClient.Contact> staff = new LinkedHashMap<>();
        for (String role : List.of(Role.SUPER_ADMIN.name(), Role.FINANCE_ADMIN.name())) {
            asSystem(() -> identity.platformStaff(role)).forEach(c -> {
                if (c.id() != null && (c.status() == null || "ACTIVE".equalsIgnoreCase(c.status()))) {
                    staff.putIfAbsent(c.id(), c);
                }
            });
        }
        int reminded = 0;
        for (BillingStart s : due) {
            String name = StringUtils.hasText(s.getBusinessName()) ? s.getBusinessName() : "A business";
            String title = name + ": charges start on " + LONG.format(day);
            String body = name + "'s platform fee starts on " + LONG.format(day) + " (in " + REMIND_DAYS + " days). Days from then are billed at the"
                    + " business's rate on the monthly invoice; until then they stay free. Check the date with the owner, or change it on the business's Money tab.";
            try {
                Set<String> ids = new LinkedHashSet<>(staff.keySet());
                if (!ids.isEmpty()) {
                    EventPublisher pub = events.getIfAvailable();
                    if (pub != null) {
                        asSystem(() -> {
                            pub.publish(KafkaTopics.NOTICE, NoticeEvent.builder().kind(NOTICE_KIND).title(title).body(body)
                                    .link("/dashboard/businesses/" + s.getBusinessId() + "?tab=money").userIds(new ArrayList<>(ids)).build());
                            return null;
                        });
                    }
                }
                for (IdentityClient.Contact c : staff.values()) {
                    if (StringUtils.hasText(c.email())) {
                        String hello = "Hello " + (StringUtils.hasText(c.fullName()) ? c.fullName().trim() : "there") + ",\n\n";
                        deliveries.send(Delivery.email(c.email(), "Hospio360: " + title, hello + body + "\n\nHospio360", Delivery.Kind.NOTICE,
                                "charges-start:" + s.getBusinessId() + ":" + day + ":" + c.id()));
                    }
                }
                s.setRemindedFor(day);
                asSystem(() -> starts.save(s));
                reminded++;
                names.add(name);
            } catch (RuntimeException e) {
                log.warn("Charges start reminder for {} not sent: {}", name, e.getMessage());
            }
        }
        log.info("Charges start reminders for {}: {} of {} ({})", day, reminded, due.size(), names);
        return new ReminderRun(today, day, due.size(), reminded, names);
    }

    private boolean billable(BillingStart s) {
        return !qaOnly || qaBusinessIds.contains(s.getBusinessId()) || MonthlyInvoiceService.qaName(s.getBusinessName());
    }

    static <T> T asSystem(Supplier<T> work) {
        return TenantContext.runWith(Tenant.system("charges-start"), work);
    }
}
