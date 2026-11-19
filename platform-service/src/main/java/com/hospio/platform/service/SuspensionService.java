package com.hospio.platform.service;

import com.hospio.common.billing.BillingHold;
import com.hospio.common.delivery.Delivery;
import com.hospio.common.delivery.DeliveryService;
import com.hospio.common.enums.Role;
import com.hospio.common.exception.BadRequestException;
import com.hospio.common.exception.ForbiddenException;
import com.hospio.common.kafka.EventPublisher;
import com.hospio.common.kafka.KafkaTopics;
import com.hospio.common.kafka.events.NoticeEvent;
import com.hospio.common.security.CurrentUser;
import com.hospio.common.security.HospioUserDetails;
import com.hospio.common.suspension.BusinessSuspension;
import com.hospio.common.tenancy.Tenant;
import com.hospio.common.tenancy.TenantContext;
import com.hospio.common.util.Money;
import com.hospio.platform.client.TenantClient;
import com.hospio.platform.dto.PlatformRequests;
import com.hospio.platform.entity.MonthlyInvoice;
import com.hospio.platform.entity.SuspensionNotice;
import com.hospio.platform.repository.MonthlyInvoiceRepository;
import com.hospio.platform.repository.SuspensionNoticeRepository;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Real suspension of a business (docs/suspension.md; Terms 7.3, Fees 5.5). tenant-service holds the state (the stage SUSPENDED, why,
 * the stage to put back) and every service refuses the business's people while it is on (common {@code SuspensionFilter}). This service
 * runs the steps around it: who is 30 or more days overdue, the 7 days' notice, suspending, reactivating (by an admin, or by itself when
 * a business suspended for unpaid invoices has paid every overdue one), with the emails, bell notices and the audit trail.
 */
@Slf4j
@Service
public class SuspensionService {

    public static final String UNPAID_INVOICE = "UNPAID_INVOICE";
    public static final String OTHER = "OTHER";
    /** Terms 7.3: an invoice unpaid this many days after its due date allows a suspension… */
    public static final int OVERDUE_DAYS = 30;
    /** …after this many days' notice. */
    public static final int NOTICE_DAYS = 7;
    static final String AREA = "Suspension";
    private static final List<String> OPEN = List.of(MonthlyInvoice.DUE, MonthlyInvoice.OVERDUE);

    private final MonthlyInvoiceRepository invoices;
    private final SuspensionNoticeRepository notices;
    private final MonthlyInvoiceService billing;
    private final TenantClient tenant;
    private final DeliveryService deliveries;
    private final ObjectProvider<EventPublisher> publisher;
    private final ChangeService changes;
    private final BillingHold holds;
    private final BusinessSuspension suspension;
    private final String hotelAppUrl;
    private final boolean qaOnly;

    public SuspensionService(MonthlyInvoiceRepository invoices, SuspensionNoticeRepository notices, MonthlyInvoiceService billing, TenantClient tenant,
                             DeliveryService deliveries, ObjectProvider<EventPublisher> publisher, ChangeService changes, BillingHold holds,
                             BusinessSuspension suspension,
                             @Value("${hospio.apps.hotel-url:https://web-staging.hospio360.com}") String hotelAppUrl,
                             @Value("${hospio.billing.qa-only:false}") boolean qaOnly) {
        this.invoices = invoices;
        this.notices = notices;
        this.billing = billing;
        this.tenant = tenant;
        this.deliveries = deliveries;
        this.publisher = publisher;
        this.changes = changes;
        this.holds = holds;
        this.suspension = suspension;
        this.hotelAppUrl = hotelAppUrl.replaceAll("/+$", "");
        this.qaOnly = qaOnly;
    }

    // ---- views ----------------------------------------------------------------------------------------------------

    public record OverdueInvoice(String id, String number, String monthLabel, BigDecimal outstanding, LocalDate dueOn, long daysOverdue,
                                 boolean restricted) {
    }

    public record NoticeView(String id, LocalDate sentOn, LocalDate suspendOn, String invoiceNumbers, BigDecimal total, String recipient,
                             String outcome, String sentByName, Instant createdAt) {
        static NoticeView of(SuspensionNotice n) {
            return n == null ? null : new NoticeView(n.getId(), n.getSentOn(), n.getSuspendOn(), n.getInvoiceNumbers(), n.getTotal(),
                    n.getRecipient(), n.getOutcome(), n.getSentByName(), n.getCreatedAt());
        }
    }

    /**
     * A business's suspension as the admin portal shows it. {@code candidate}: an invoice is {@value #OVERDUE_DAYS}+ days past due and the
     * business is not suspended. {@code warnings}: what a super admin is told before (and after) suspending outside the Terms' steps.
     */
    public record State(String businessId, String businessName, String stage, boolean suspended, Instant suspendedAt, String reasonType,
                        String reason, boolean showReason, LocalDate noticeSentOn, String previousStage, String suspendedByName,
                        List<OverdueInvoice> overdue, BigDecimal overdueTotal, long oldestDaysOverdue, boolean candidate, NoticeView lastNotice,
                        List<String> warnings) {
    }

    public record Candidate(String businessId, String businessName, String stage, String ownerName, String ownerEmail, List<OverdueInvoice> overdue,
                            BigDecimal overdueTotal, long oldestDaysOverdue, NoticeView lastNotice, boolean noticeElapsed) {
    }

    public record SuspendRequest(String reasonType, String note, Boolean showNote, Boolean noticeSent, LocalDate noticeSentOn) {
    }

    public record ReactivateRequest(String note) {
    }

    public record NoticeRequest(LocalDate suspendOn) {
    }

    /** The subject and text of an email (QA preview). */
    public record Mail(String subject, String text, String bellTitle, String bellBody) {
    }

    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public State state(String businessId) {
        TenantClient.SuspensionDetail d = tenant.suspensionDetail(businessId);
        return state(d, List.of());
    }

    private State state(TenantClient.SuspensionDetail d, List<String> warnings) {
        LocalDate today = today();
        List<OverdueInvoice> overdue = overdue(d.id(), today);
        long oldest = overdue.stream().mapToLong(OverdueInvoice::daysOverdue).max().orElse(0);
        NoticeView last = NoticeView.of(asSystem(() -> notices.findFirstByBusinessIdOrderByCreatedAtDesc(d.id()).orElse(null)));
        return new State(d.id(), d.name(), d.stage(), d.suspended(), d.suspendedAt(), d.suspensionType(), d.suspensionReason(),
                d.suspensionShowReason(), d.suspensionNoticeSentOn(), d.stageBeforeSuspension(), d.suspendedByName(), overdue, total(overdue),
                oldest, !d.suspended() && oldest >= OVERDUE_DAYS, last, warnings);
    }

    /** Open invoices past their due date, oldest first. */
    List<OverdueInvoice> overdue(String businessId, LocalDate today) {
        return asSystem(() -> invoices.findByBusinessIdAndStatusIn(businessId, OPEN)).stream().filter(i -> today.isAfter(i.getDueOn()))
                .sorted(Comparator.comparing(MonthlyInvoice::getDueOn)).map(i -> view(i, today)).toList();
    }

    private static OverdueInvoice view(MonthlyInvoice i, LocalDate today) {
        return new OverdueInvoice(i.getId(), i.getNumber(), MonthlyInvoiceService.MONTH.format(YearMonth.parse(i.getMonth())), i.outstanding(),
                i.getDueOn(), ChronoUnit.DAYS.between(i.getDueOn(), today), i.restricted());
    }

    private static BigDecimal total(List<OverdueInvoice> overdue) {
        return Money.of(overdue.stream().map(OverdueInvoice::outstanding).reduce(BigDecimal.ZERO, BigDecimal::add));
    }

    /** "Suspension candidates": businesses not suspended with an invoice {@value #OVERDUE_DAYS}+ days past its due date, longest overdue first. */
    public List<Candidate> candidates() {
        LocalDate today = today();
        Map<String, List<MonthlyInvoice>> byBusiness = asSystem(() -> invoices.findByStatusIn(OPEN)).stream()
                .filter(i -> today.isAfter(i.getDueOn())).collect(Collectors.groupingBy(MonthlyInvoice::getBusinessId, LinkedHashMap::new, Collectors.toList()));
        List<Candidate> out = new ArrayList<>();
        byBusiness.forEach((businessId, list) -> {
            List<OverdueInvoice> overdue = list.stream().sorted(Comparator.comparing(MonthlyInvoice::getDueOn)).map(i -> view(i, today)).toList();
            long oldest = overdue.stream().mapToLong(OverdueInvoice::daysOverdue).max().orElse(0);
            if (oldest < OVERDUE_DAYS) {
                return;
            }
            TenantClient.Business b = asSystem(() -> tenant.business(businessId)).orElse(null);
            if (b == null || "SUSPENDED".equals(b.stage()) || !billing.billable(b)) {
                return;
            }
            NoticeView last = NoticeView.of(asSystem(() -> notices.findFirstByBusinessIdOrderByCreatedAtDesc(businessId).orElse(null)));
            out.add(new Candidate(businessId, b.name(), b.stage(), b.ownerName(), b.ownerEmail(), overdue, total(overdue), oldest, last,
                    last != null && !today.isBefore(last.suspendOn())));
        });
        out.sort(Comparator.comparingLong(Candidate::oldestDaysOverdue).reversed());
        return out;
    }

    // ---- the 7 days' notice ----------------------------------------------------------------------------------------

    /**
     * Emails the owner (and puts a bell notice in front of owners and managers) that the account will be suspended on {@code suspendOn}
     * (today + {@value #NOTICE_DAYS} days when not given, never sooner) unless the overdue invoices are paid; records the notice.
     */
    public State sendNotice(String businessId, NoticeRequest r) {
        HospioUserDetails admin = requireMoney();
        TenantClient.Business b = business(businessId);
        TenantClient.SuspensionDetail d = tenant.suspensionDetail(businessId);
        if (d.suspended()) {
            throw new BadRequestException(d.name() + " is already suspended");
        }
        LocalDate today = today();
        List<OverdueInvoice> overdue = overdue(businessId, today);
        if (overdue.stream().noneMatch(i -> i.daysOverdue() >= OVERDUE_DAYS)) {
            throw new BadRequestException("A suspension notice needs an invoice unpaid " + OVERDUE_DAYS + " days after its due date. "
                    + (overdue.isEmpty() ? d.name() + " has nothing overdue" : "The oldest is " + overdue.get(0).daysOverdue() + " days overdue"));
        }
        LocalDate suspendOn = r == null || r.suspendOn() == null ? today.plusDays(NOTICE_DAYS) : r.suspendOn();
        if (suspendOn.isBefore(today.plusDays(NOTICE_DAYS))) {
            throw new BadRequestException("The suspension date must give at least " + NOTICE_DAYS + " days' notice: "
                    + MonthlyInvoiceService.DAY.format(today.plusDays(NOTICE_DAYS)) + " or later");
        }
        Mail mail = noticeMail(b, overdue, suspendOn);
        String to = StringUtils.hasText(b.ownerEmail()) ? b.ownerEmail().trim() : null;
        SuspensionNotice n = asSystem(() -> notices.save(SuspensionNotice.builder().businessId(businessId).businessName(b.name()).sentOn(today)
                .suspendOn(suspendOn).invoiceNumbers(MonthlyInvoiceService.cut(numbers(overdue), 1000)).total(total(overdue)).recipient(to)
                .outcome(to == null ? "NO_EMAIL" : "EMAILED").sentBy(admin.getUserId()).sentByName(admin.getFullName()).build()));
        if (to != null) {
            deliveries.send(List.of(Delivery.email(to, mail.subject(), mail.text(), Delivery.Kind.INVOICE_FINAL, "suspension-notice:" + n.getId())));
        }
        bell(b, "suspension-notice:" + n.getId(), mail.bellTitle(), mail.bellBody());
        audit(businessId, "Suspension notice sent to " + (to == null ? "nobody (the owner has no email)" : to) + ": the account will be suspended on "
                        + MonthlyInvoiceService.DAY.format(suspendOn) + " unless " + numbers(overdue) + " (NGN " + MonthlyInvoiceService.naira(total(overdue))
                        + ") " + (overdue.size() == 1 ? "is" : "are") + " paid",
                "7 days' notice before suspension (Terms 7.3)", null, "{\"noticeSentOn\":\"" + today + "\",\"suspendOn\":\"" + suspendOn + "\"}",
                admin.getUserId(), admin.getFullName());
        log.warn("Suspension notice for {} ({}): suspend on {} unless {} paid", b.name(), businessId, suspendOn, numbers(overdue));
        return state(d, to == null ? List.of("The owner has no email address on file: nothing was emailed. Tell them another way") : List.of());
    }

    // ---- suspend ---------------------------------------------------------------------------------------------------

    /**
     * Suspends a Live or At risk business. UNPAID_INVOICE follows the Terms: an invoice 30+ days overdue and a notice sent at least 7 days
     * ago; a finance admin is refused outside that, a super admin is let through with the warnings recorded. OTHER (fraud, a breach of
     * the Terms, the owner asked) is for super admins only. From then on every request by the business's people is refused.
     */
    public State suspend(String businessId, SuspendRequest r) {
        HospioUserDetails admin = requireMoney();
        if (r == null || !StringUtils.hasText(r.reasonType())) {
            throw new BadRequestException("Choose why: unpaid invoice, or another reason");
        }
        String type = r.reasonType().trim().toUpperCase(java.util.Locale.ROOT);
        if (!UNPAID_INVOICE.equals(type) && !OTHER.equals(type)) {
            throw new BadRequestException("The reason type is UNPAID_INVOICE or OTHER");
        }
        String note = r.note() == null ? "" : r.note().trim();
        if (note.length() < 8) {
            throw new BadRequestException("Write a note saying why (at least 8 characters). It is kept on the business and in the audit trail");
        }
        if (note.length() > 500) {
            throw new BadRequestException("Keep the note under 500 characters");
        }
        TenantClient.Business b = business(businessId);
        boolean superAdmin = admin.has(Role.SUPER_ADMIN);
        LocalDate today = today();
        List<String> warnings = new ArrayList<>();
        LocalDate noticeSentOn = null;
        if (UNPAID_INVOICE.equals(type)) {
            List<OverdueInvoice> overdue = overdue(businessId, today);
            if (overdue.isEmpty()) {
                throw new BadRequestException(b.name() + " has no overdue invoice. Choose another reason, or check Billing");
            }
            long oldest = overdue.stream().mapToLong(OverdueInvoice::daysOverdue).max().orElse(0);
            if (oldest < OVERDUE_DAYS) {
                warnings.add("The oldest unpaid invoice is " + oldest + " days overdue; the Terms allow a suspension at " + OVERDUE_DAYS + " days");
            }
            if (!Boolean.TRUE.equals(r.noticeSent()) || r.noticeSentOn() == null) {
                warnings.add("No 7 days' notice was recorded; the Terms need one before a suspension for unpaid invoices");
            } else {
                noticeSentOn = r.noticeSentOn();
                if (noticeSentOn.isAfter(today)) {
                    throw new BadRequestException("The notice date can't be in the future");
                }
                if (noticeSentOn.plusDays(NOTICE_DAYS).isAfter(today)) {
                    warnings.add("The notice was sent on " + MonthlyInvoiceService.DAY.format(noticeSentOn) + ": the 7 days end on "
                            + MonthlyInvoiceService.DAY.format(noticeSentOn.plusDays(NOTICE_DAYS)));
                }
            }
            if (!warnings.isEmpty() && !superAdmin) {
                throw new ForbiddenException(warnings.get(0) + ". Only a super admin can suspend before that");
            }
        } else if (!superAdmin) {
            throw new ForbiddenException("Only a super admin can suspend a business for a reason other than unpaid invoices");
        }
        TenantClient.SuspensionDetail before = tenant.suspensionDetail(businessId);
        TenantClient.SuspensionDetail after = tenant.suspend(businessId, type, note, Boolean.TRUE.equals(r.showNote()), noticeSentOn,
                admin.getUserId(), admin.getFullName());
        suspension.forget(businessId);
        Mail mail = suspendedMail(b, type, Boolean.TRUE.equals(r.showNote()) ? note : null, overdue(businessId, today));
        tell(b, mail, "suspended:" + businessId + ":" + Instant.now().getEpochSecond());
        audit(businessId, "Suspended (" + (UNPAID_INVOICE.equals(type) ? "unpaid invoice" : "other reason") + "): nobody at the business can use "
                        + "Hospio360 until it is reactivated" + (noticeSentOn == null ? "" : "; notice sent " + noticeSentOn)
                        + (warnings.isEmpty() ? "" : ". Warnings accepted: " + String.join("; ", warnings)),
                note, "{\"stage\":\"" + before.stage() + "\"}", "{\"stage\":\"SUSPENDED\",\"reasonType\":\"" + type + "\",\"showNote\":"
                        + Boolean.TRUE.equals(r.showNote()) + ",\"noticeSentOn\":" + (noticeSentOn == null ? "null" : "\"" + noticeSentOn + "\"") + "}",
                admin.getUserId(), admin.getFullName());
        log.warn("Business {} ({}) suspended by {}: {} · {}", b.name(), businessId, admin.getFullName(), type, note);
        return state(after, warnings);
    }

    // ---- reactivate ------------------------------------------------------------------------------------------------

    /** An admin lifts the suspension: back to the stage it had. A suspension for another reason is lifted by a super admin only. */
    public State reactivate(String businessId, ReactivateRequest r) {
        HospioUserDetails admin = requireMoney();
        String note = r == null || r.note() == null ? "" : r.note().trim();
        if (note.length() < 8) {
            throw new BadRequestException("Write a note saying why it is reactivated (at least 8 characters)");
        }
        TenantClient.Business b = business(businessId);
        TenantClient.SuspensionDetail before = tenant.suspensionDetail(businessId);
        if (!before.suspended()) {
            throw new BadRequestException(before.name() + " isn't suspended");
        }
        if (!UNPAID_INVOICE.equals(before.suspensionType()) && !admin.has(Role.SUPER_ADMIN)) {
            throw new ForbiddenException("Only a super admin can reactivate a business suspended for a reason other than unpaid invoices");
        }
        return lift(b, before, note, admin.getUserId(), admin.getFullName(), false);
    }

    /**
     * After a payment: a business suspended for unpaid invoices that has no overdue invoice left is reactivated at once. Suspended for
     * another reason, or with an overdue invoice still open, it stays suspended. Never throws (the payment stands whatever happens here).
     */
    public boolean afterPayment(String businessId) {
        try {
            TenantClient.SuspensionDetail d = tenant.suspensionDetail(businessId);
            if (!d.suspended() || !UNPAID_INVOICE.equals(d.suspensionType())) {
                return false;
            }
            List<OverdueInvoice> left = overdue(businessId, today());
            if (!left.isEmpty()) {
                log.info("Business {} stays suspended: {} still overdue", d.name(), numbers(left));
                return false;
            }
            TenantClient.Business b = asSystem(() -> tenant.business(businessId)).orElse(null);
            if (b == null) {
                return false;
            }
            lift(b, d, "Every overdue invoice is paid in full", "system", "automatic", true);
            return true;
        } catch (RuntimeException e) {
            log.error("Business {} not reactivated after its payment: {}", businessId, e.getMessage(), e);
            return false;
        }
    }

    private State lift(TenantClient.Business b, TenantClient.SuspensionDetail before, String note, String actorId, String actorName, boolean automatic) {
        TenantClient.SuspensionDetail after = tenant.reactivate(b.id());
        suspension.forget(b.id());
        holds.forget(b.id());
        Mail mail = reactivatedMail(b, automatic);
        tell(b, mail, "reactivated:" + b.id() + ":" + Instant.now().getEpochSecond());
        audit(b.id(), "Reactivated" + (automatic ? " automatically" : "") + ": back to " + label(after.stage()) + ", everyone can use Hospio360 again",
                note, "{\"stage\":\"SUSPENDED\",\"reasonType\":\"" + before.suspensionType() + "\"}", "{\"stage\":\"" + after.stage() + "\"}",
                actorId, actorName);
        log.warn("Business {} ({}) reactivated by {}: {}", b.name(), b.id(), actorName, note);
        return state(after, List.of());
    }

    // ---- words -----------------------------------------------------------------------------------------------------

    Mail noticeMail(TenantClient.Business b, List<OverdueInvoice> overdue, LocalDate suspendOn) {
        String day = MonthlyInvoiceService.DAY.format(suspendOn);
        StringBuilder t = new StringBuilder(hello(b));
        t.append(overdue.size() == 1 ? "This Hospio360 invoice is" : "These Hospio360 invoices are").append(" more than ").append(OVERDUE_DAYS)
                .append(" days past ").append(overdue.size() == 1 ? "its" : "their").append(" due date and still unpaid:\n\n");
        for (OverdueInvoice i : overdue) {
            t.append("- ").append(i.number()).append(" (").append(i.monthLabel()).append("): NGN ").append(MonthlyInvoiceService.naira(i.outstanding()))
                    .append(", due ").append(MonthlyInvoiceService.DAY.format(i.dueOn())).append('\n');
        }
        t.append("\nBusiness: ").append(b.name()).append("\nAmount due: NGN ").append(MonthlyInvoiceService.naira(total(overdue)))
                .append("\nSuspension date: ").append(day).append("\n\n");
        t.append("This is your ").append(NOTICE_DAYS).append(" days' notice under clause 7.3 of the Terms of Service. Unless the amount due is paid in full, your Hospio360 account will be suspended on ")
                .append(day).append(". While it is suspended nobody at ").append(b.name())
                .append(" can use Hospio360: the owner can still sign in to see and pay the invoices, and nothing is deleted.\n\n");
        t.append("Pay now (card, bank transfer or USSD): ").append(hotelAppUrl).append("/dashboard/billing\n\n");
        t.append("If you have paid already, or think this is a mistake, reply to this email or write to ").append(BusinessSuspension.CONTACT).append(".");
        t.append(footer());
        return new Mail("Notice: your Hospio360 account will be suspended on " + day + " unless " + numbers(overdue) + " "
                + (overdue.size() == 1 ? "is" : "are") + " paid", t.toString(), "Account to be suspended on " + MonthlyInvoiceService.SHORT.format(suspendOn),
                "Unless " + numbers(overdue) + " (NGN " + MonthlyInvoiceService.naira(total(overdue)) + ") "
                        + (overdue.size() == 1 ? "is" : "are") + " paid. The owner can pay under Billing & invoices");
    }

    Mail suspendedMail(TenantClient.Business b, String type, String shownNote, List<OverdueInvoice> overdue) {
        StringBuilder t = new StringBuilder(hello(b));
        t.append("The Hospio360 account for ").append(b.name()).append(" is suspended from today. Nobody at the business can use Hospio360 until it is reactivated. Your data is kept: nothing is deleted.\n\n");
        if (StringUtils.hasText(shownNote)) {
            t.append("Reason: ").append(shownNote).append("\n\n");
        }
        if (UNPAID_INVOICE.equals(type)) {
            t.append("It is suspended because ").append(overdue.size() == 1 ? "this invoice is" : "these invoices are").append(" unpaid:\n\n");
            for (OverdueInvoice i : overdue) {
                t.append("- ").append(i.number()).append(" (").append(i.monthLabel()).append("): NGN ").append(MonthlyInvoiceService.naira(i.outstanding())).append('\n');
            }
            t.append("\nAmount due: NGN ").append(MonthlyInvoiceService.naira(total(overdue))).append("\n\n");
            t.append("To lift it: sign in as the owner and pay every overdue invoice in full. The account comes back by itself within a minute of the payment.\n\n");
            t.append("Sign in and pay: ").append(hotelAppUrl).append("/dashboard/billing\n\n");
        } else {
            t.append("To talk about it, reply to this email or write to ").append(BusinessSuspension.CONTACT).append(". Only Hospio360 can lift this suspension.\n\n");
        }
        t.append("Questions: ").append(BusinessSuspension.CONTACT).append(footer());
        return new Mail("Your Hospio360 account for " + b.name() + " is suspended", t.toString(), "Account suspended",
                UNPAID_INVOICE.equals(type) ? "Unpaid invoices. The owner can sign in and pay to lift it" : "Contact Hospio360 at " + BusinessSuspension.CONTACT);
    }

    Mail reactivatedMail(TenantClient.Business b, boolean automatic) {
        String t = hello(b) + "The Hospio360 account for " + b.name() + " is active again" + (automatic ? ": every overdue invoice is paid, thank you" : "")
                + ". Everyone can sign in and work as before; anyone looking at the suspended screen gets back in within a minute.\n\n"
                + "Open Hospio360: " + hotelAppUrl + "\n\nQuestions: " + BusinessSuspension.CONTACT + footer();
        return new Mail("Your Hospio360 account for " + b.name() + " is active again", t, "Account active again",
                automatic ? "Every overdue invoice is paid. Thank you" : "Hospio360 lifted the suspension");
    }

    /** QA: what each email would say for the business as it is now. */
    public Mail preview(String businessId, String kind) {
        TenantClient.Business b = business(businessId);
        TenantClient.SuspensionDetail d = tenant.suspensionDetail(businessId);
        List<OverdueInvoice> overdue = overdue(businessId, today());
        return switch (kind == null ? "" : kind.toUpperCase(java.util.Locale.ROOT)) {
            case "NOTICE" -> noticeMail(b, overdue, today().plusDays(NOTICE_DAYS));
            case "SUSPENDED" -> suspendedMail(b, d.suspended() && d.suspensionType() != null ? d.suspensionType() : UNPAID_INVOICE,
                    d.suspensionShowReason() ? d.suspensionReason() : null, overdue);
            case "REACTIVATED" -> reactivatedMail(b, true);
            default -> throw new BadRequestException("kind is NOTICE, SUSPENDED or REACTIVATED");
        };
    }

    // ---- helpers ---------------------------------------------------------------------------------------------------

    private void tell(TenantClient.Business b, Mail mail, String ref) {
        if (StringUtils.hasText(b.ownerEmail())) {
            deliveries.send(List.of(Delivery.email(b.ownerEmail().trim(), mail.subject(), mail.text(), Delivery.Kind.NOTICE, MonthlyInvoiceService.cut(ref, 80))));
        }
        bell(b, ref, mail.bellTitle(), mail.bellBody());
    }

    /** One bell notice per location, for its owners and managers. */
    private void bell(TenantClient.Business b, String ref, String title, String body) {
        EventPublisher p = publisher.getIfAvailable();
        if (p == null) {
            return;
        }
        for (TenantClient.Hotel h : b.hotels()) {
            NoticeEvent e = NoticeEvent.builder().kind(MonthlyInvoiceService.NOTICE_KIND).title(title).body(body).link("/billing")
                    .roles(List.of("OWNER", "MANAGER")).build();
            e.setEventId(UUID.nameUUIDFromBytes((ref + ":" + h.id()).getBytes(StandardCharsets.UTF_8)).toString());
            e.setBusinessId(b.id());
            e.setHotelId(h.id());
            try {
                p.publish(KafkaTopics.NOTICE, e);
            } catch (RuntimeException ex) {
                log.warn("Bell notice {} for {} not sent: {}", ref, b.name(), ex.getMessage());
            }
        }
    }

    private void audit(String businessId, String description, String reason, String before, String after, String actorId, String actorName) {
        try {
            asSystem(() -> changes.recordAs(new PlatformRequests.PlatformChangeRequest(businessId, null, AREA, MonthlyInvoiceService.cut(description, 500),
                    reason, before, after), actorId, actorName));
        } catch (RuntimeException e) {
            log.error("Suspension change for {} not recorded in the audit trail: {} ({})", businessId, description, e.getMessage(), e);
        }
    }

    /** The business, and on staging only a QA one (hospio.billing.qa-only): real businesses there are never noticed or suspended. */
    private TenantClient.Business business(String businessId) {
        TenantClient.Business b = asSystem(() -> tenant.business(businessId))
                .orElseThrow(() -> new com.hospio.common.exception.ResourceNotFoundException("Business not found"));
        if (qaOnly && !billing.billable(b)) {
            throw new BadRequestException("Staging only suspends QA businesses (hospio.billing.qa-only): " + b.name() + " isn't one");
        }
        return b;
    }

    private static HospioUserDetails requireMoney() {
        HospioUserDetails user = CurrentUser.require();
        if (!user.isPlatform() || !(user.has(Role.SUPER_ADMIN) || user.has(Role.FINANCE_ADMIN))) {
            throw new ForbiddenException("Only a super admin or a finance admin can do this");
        }
        return user;
    }

    private static String numbers(List<OverdueInvoice> overdue) {
        return overdue.stream().map(OverdueInvoice::number).collect(Collectors.joining(", "));
    }

    private static String hello(TenantClient.Business b) {
        return "Hello " + (StringUtils.hasText(b.ownerName()) ? b.ownerName().trim() : "there") + ",\n\n";
    }

    private static String footer() {
        return "\n\nHospio360 · " + MonthlyInvoiceService.COMPANY_LINE;
    }

    private static String label(String stage) {
        return "AT_RISK".equals(stage) ? "At risk" : stage == null ? "Live" : stage.charAt(0) + stage.substring(1).toLowerCase(java.util.Locale.ROOT);
    }

    private static LocalDate today() {
        return LocalDate.now(MonthlyInvoiceService.LAGOS);
    }

    private static <T> T asSystem(Supplier<T> work) {
        return TenantContext.runWith(Tenant.system("suspension"), work);
    }
}
