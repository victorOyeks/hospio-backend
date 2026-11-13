package com.hospio.messaging.billing;

import com.hospio.common.exception.BadRequestException;
import com.hospio.common.exception.ResourceNotFoundException;
import com.hospio.common.kafka.events.DeliveryRequestedEvent;
import com.hospio.common.security.CurrentUser;
import com.hospio.common.security.HospioUserDetails;
import com.hospio.common.tenancy.Tenant;
import com.hospio.common.tenancy.TenantContext;
import com.hospio.messaging.client.TenantClient;
import com.hospio.messaging.entity.DeliveryRecord;
import com.hospio.messaging.repository.DeliveryRecordRepository;
import com.hospio.messaging.service.NotificationService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

/**
 * Guest messages as a paid service (docs/guest-message-billing.md). A hotel switches it on; from then every email, SMS or
 * WhatsApp it sends outside (to guests, companies, suppliers) is priced when it goes out, by the channel it actually went on
 * (a WhatsApp that went as an SMS is an SMS), and a message the network rejected costs nothing. On the 1st (Lagos) last
 * month's messages become one invoice per hotel, due ten days later; an invoice still unpaid after that suspends the
 * hotel's guest messages until it is paid. Codes, sign-in, invites and resets are never billable and always go.
 */
@Slf4j
@Service
public class CommBilling {

    /** What a hotel pays for: everything it sends outside. Everything else (codes, sign-in, staff, platform) is free. */
    public static final Set<String> BILLABLE_KINDS = Set.of("BOOKING_CONFIRMATION", "BOOKING_CHANGED", "BOOKING_CANCELLED", "RECEIPT",
            "FOLIO", "STAY_SUMMARY", "MESSAGE", "STATEMENT", "PAYMENT_REMINDER", "PURCHASE_ORDER");

    /** What counts as gone out (a FAILED one, e.g. rejected for DND, is not charged). */
    public static final List<DeliveryRecord.Status> CHARGED = List.of(DeliveryRecord.Status.SENT, DeliveryRecord.Status.DELIVERED);

    public static final ZoneId LAGOS = ZoneId.of("Africa/Lagos");
    public static final int GRACE_DAYS = 10;

    /** Effective prices per message, in naira. */
    public record Prices(BigDecimal email, BigDecimal sms, BigDecimal whatsapp) {
        public BigDecimal of(String channel) {
            return switch (channel == null ? "" : channel.toUpperCase(Locale.ROOT)) {
                case "SMS" -> sms;
                case "WHATSAPP" -> whatsapp;
                default -> email;
            };
        }
    }

    /** Whether a message is billable and, if so, whether the hotel may send it now (and why not). */
    public record Gate(boolean billable, boolean allowed, String reason) {
        public static final Gate FREE = new Gate(false, true, null);
    }

    /** A month's usage so far: per channel counts and amounts. */
    public record Usage(int emailCount, int smsCount, int whatsappCount, BigDecimal emailAmount, BigDecimal smsAmount, BigDecimal whatsappAmount,
                        BigDecimal total) {
    }

    /** A hotel's service as the screens show it. */
    public record PlanView(String hotelId, String hotelName, boolean enabled, Prices prices, boolean customPrices, Prices defaults,
                           boolean suspended, String suspendedReason, Instant suspendedAt, String changedBy, Instant changedAt,
                           String month, Usage thisMonth, BigDecimal outstanding, int openInvoices, boolean onlinePayment) {
    }

    /** One line of an invoice: a kind of message by channel. */
    public record Line(String kind, String label, String channel, long count, BigDecimal unitPrice, BigDecimal amount) {
    }

    public record InvoiceView(CommInvoice invoice, List<Line> lines) {
    }

    private final CommPlanRepository plans;
    private final CommPricesRepository prices;
    private final CommInvoiceRepository invoices;
    private final DeliveryRecordRepository deliveries;
    private final TenantClient tenants;
    private final NotificationService notifications;
    private final PaystackClient paystack;
    private final TransactionTemplate tx;
    private final com.hospio.messaging.mail.MailDispatcher mail;
    private final String hotelAppUrl;

    public CommBilling(CommPlanRepository plans, CommPricesRepository prices, CommInvoiceRepository invoices, DeliveryRecordRepository deliveries,
                       TenantClient tenants, NotificationService notifications, PaystackClient paystack, TransactionTemplate tx,
                       @Lazy com.hospio.messaging.mail.MailDispatcher mail,
                       @Value("${hospio.apps.hotel-url:https://web-staging.hospio360.com}") String hotelAppUrl) {
        this.plans = plans;
        this.prices = prices;
        this.invoices = invoices;
        this.deliveries = deliveries;
        this.tenants = tenants;
        this.notifications = notifications;
        this.paystack = paystack;
        this.tx = tx;
        this.mail = mail;
        this.hotelAppUrl = hotelAppUrl.replaceAll("/+$", "");
    }

    // ---- gating and pricing (called by the sender, as the system) -------------------------------------------------

    /** Is this message billable, and may the hotel send it? Codes, sign-in and staff messages are free and always go. */
    public Gate gate(String hotelId, String kind) {
        if (hotelId == null || kind == null || !BILLABLE_KINDS.contains(kind.toUpperCase(Locale.ROOT))) {
            return Gate.FREE;
        }
        Optional<CommPlan> plan = asSystem(() -> plans.findByHotelId(hotelId));
        if (plan.isEmpty() || !plan.get().isEnabled()) {
            return new Gate(true, false, "Guest messages are off for this hotel (Hotel setup → Guest messages)");
        }
        if (plan.get().getSuspendedAt() != null) {
            return new Gate(true, false, "Guest messages are suspended: " + nz(plan.get().getSuspendedReason()));
        }
        return new Gate(true, true, null);
    }

    /** The price of one message at this hotel by the channel it went on. */
    public BigDecimal price(String hotelId, String channel) {
        return effective(asSystem(() -> plans.findByHotelId(hotelId)).orElse(null)).of(channel);
    }

    public Prices defaults() {
        CommPrices p = asSystem(() -> prices.findById("DEFAULT")).orElse(null);
        return p == null ? new Prices(new BigDecimal("5.00"), new BigDecimal("10.00"), new BigDecimal("25.00"))
                : new Prices(p.getEmailPrice(), p.getSmsPrice(), p.getWhatsappPrice());
    }

    private Prices effective(CommPlan plan) {
        Prices d = defaults();
        if (plan == null) {
            return d;
        }
        return new Prices(plan.getEmailPrice() == null ? d.email() : plan.getEmailPrice(), plan.getSmsPrice() == null ? d.sms() : plan.getSmsPrice(),
                plan.getWhatsappPrice() == null ? d.whatsapp() : plan.getWhatsappPrice());
    }

    // ---- the hotel's own screen ----------------------------------------------------------------------------------------

    public PlanView plan(String hotelId) {
        CommPlan plan = plans.findByHotelId(hotelId).orElse(null);
        YearMonth month = YearMonth.now(LAGOS);
        Usage usage = usage(hotelId, month);
        List<CommInvoice> open = invoices.findByHotelIdAndStatusIn(hotelId, List.of(CommInvoice.Status.DUE, CommInvoice.Status.OVERDUE));
        BigDecimal outstanding = open.stream().map(CommInvoice::getTotalAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        boolean custom = plan != null && (plan.getEmailPrice() != null || plan.getSmsPrice() != null || plan.getWhatsappPrice() != null);
        return new PlanView(hotelId, tenants.hotel(hotelId).map(TenantClient.Sender::name).orElse(null), plan != null && plan.isEnabled(),
                effective(plan), custom, defaults(), plan != null && plan.getSuspendedAt() != null, plan == null ? null : plan.getSuspendedReason(),
                plan == null ? null : plan.getSuspendedAt(), plan == null ? null : plan.getChangedBy(), plan == null ? null : plan.getChangedAt(),
                month.toString(), usage, outstanding, open.size(), paystack.configured());
    }

    /** Owner or manager switches the service on or off for their hotel. */
    public PlanView setEnabled(String businessId, String hotelId, boolean enabled) {
        HospioUserDetails me = CurrentUser.require();
        CommPlan plan = plans.findByHotelId(hotelId).orElseGet(() -> CommPlan.builder().businessId(businessId).hotelId(hotelId).build());
        plan.setEnabled(enabled);
        plan.setChangedBy(me.getFullName());
        plan.setChangedAt(Instant.now());
        plans.save(plan);
        log.info("Guest messages {} for hotel {} by {}", enabled ? "on" : "off", hotelId, me.getFullName());
        return plan(hotelId);
    }

    /** Usage of [month] not yet invoiced: per channel. */
    public Usage usage(String hotelId, YearMonth month) {
        Instant from = month.atDay(1).atStartOfDay(LAGOS).toInstant();
        Instant to = month.plusMonths(1).atDay(1).atStartOfDay(LAGOS).toInstant();
        int[] counts = new int[3];
        BigDecimal[] amounts = {BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO};
        for (Object[] row : deliveries.unbilledUsage(hotelId, CHARGED, from, to)) {
            int i = index((String) row[0]);
            counts[i] += ((Number) row[1]).intValue();
            amounts[i] = amounts[i].add(money(row[2]));
        }
        return new Usage(counts[0], counts[1], counts[2], amounts[0], amounts[1], amounts[2], amounts[0].add(amounts[1]).add(amounts[2]));
    }

    public Page<DeliveryRecord> thisMonthMessages(String hotelId, int page, int size) {
        Instant from = YearMonth.now(LAGOS).atDay(1).atStartOfDay(LAGOS).toInstant();
        return deliveries.unbilled(hotelId, CHARGED, from, PageRequest.of(page, size));
    }

    public Page<CommInvoice> invoicesOf(String hotelId, int page, int size) {
        return invoices.findByHotelIdOrderByMonthDesc(hotelId, PageRequest.of(page, size));
    }

    public InvoiceView invoice(String id, String hotelId) {
        CommInvoice inv = invoices.findById(id).filter(i -> hotelId == null || hotelId.equals(i.getHotelId()))
                .orElseThrow(() -> new ResourceNotFoundException("Invoice not found"));
        List<Line> lines = new ArrayList<>();
        for (Object[] row : deliveries.invoiceLines(inv.getId())) {
            long count = ((Number) row[2]).longValue();
            BigDecimal amount = money(row[3]);
            lines.add(new Line((String) row[0], label((String) row[0]), (String) row[1], count,
                    count == 0 ? BigDecimal.ZERO : amount.divide(BigDecimal.valueOf(count), 2, RoundingMode.HALF_UP), amount));
        }
        return new InvoiceView(inv, lines);
    }

    public Page<DeliveryRecord> invoiceMessages(String invoiceId, String hotelId, int page, int size) {
        invoice(invoiceId, hotelId);
        return deliveries.findByInvoiceId(invoiceId, PageRequest.of(page, size, Sort.by(Sort.Direction.ASC, "createdAt")));
    }

    // ---- paying ------------------------------------------------------------------------------------------------------

    /** Starts a Paystack checkout for an open invoice; the payer comes back to {@code returnUrl}. */
    public PaystackClient.Checkout pay(String invoiceId, String hotelId, String returnUrl) {
        HospioUserDetails me = CurrentUser.require();
        CommInvoice inv = invoice(invoiceId, hotelId).invoice();
        if (inv.getStatus() == CommInvoice.Status.PAID) {
            throw new BadRequestException("This invoice is already paid");
        }
        if (inv.getStatus() == CommInvoice.Status.VOID) {
            throw new BadRequestException("This invoice was cancelled");
        }
        if (inv.getTotalAmount().signum() <= 0) {
            throw new BadRequestException("Nothing to pay on this invoice");
        }
        String reference = inv.getNumber() + "-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
        String back = StringUtils.hasText(returnUrl) && returnUrl.startsWith(hotelAppUrl) ? returnUrl
                : hotelAppUrl + "/dashboard/hotel-setup/guest-messages?invoice=" + inv.getId();
        PaystackClient.Checkout checkout = paystack.initialize(me.getEmail(), inv.getTotalAmount(), reference, back,
                Map.of("invoice_id", inv.getId(), "invoice_number", inv.getNumber(), "hotel_id", inv.getHotelId(), "paid_by", nz(me.getFullName())));
        inv.setPaymentReference(checkout.reference());
        invoices.save(inv);
        return checkout;
    }

    /** After Paystack sends the payer back (or its webhook arrives): confirm with Paystack and settle the invoice. */
    public CommInvoice confirm(String reference, String paidByName) {
        CommInvoice inv = asSystem(() -> invoices.findByPaymentReference(reference)).orElseThrow(() -> new ResourceNotFoundException("No invoice for that payment"));
        if (inv.getStatus() == CommInvoice.Status.PAID) {
            return inv;
        }
        PaystackClient.Verified v = paystack.verify(reference);
        if (!v.success()) {
            throw new BadRequestException("Paystack hasn't confirmed this payment" + (v.gatewayResponse() == null ? "" : ": " + v.gatewayResponse()));
        }
        if (v.amount().compareTo(inv.getTotalAmount()) < 0) {
            throw new BadRequestException("Paystack received " + v.amount() + ", less than the invoice's " + inv.getTotalAmount());
        }
        return settle(inv.getId(), "PAYSTACK" + (v.channel() == null ? "" : ":" + v.channel()), reference, paidByName, null);
    }

    /** Marks an invoice paid (Paystack, or Hospio360 by hand for a transfer) and lifts a suspension it caused. */
    public CommInvoice settle(String invoiceId, String via, String reference, String paidByName, String note) {
        return asSystem(() -> tx.execute(s -> {
            CommInvoice inv = invoices.findById(invoiceId).orElseThrow(() -> new ResourceNotFoundException("Invoice not found"));
            if (inv.getStatus() == CommInvoice.Status.PAID) {
                return inv;
            }
            inv.setStatus(CommInvoice.Status.PAID);
            inv.setPaidAt(Instant.now());
            inv.setPaidVia(via);
            inv.setPaymentReference(reference);
            inv.setPaidByName(paidByName);
            if (note != null) {
                inv.setNote(note);
            }
            invoices.save(inv);
            boolean stillOverdue = invoices.findByHotelIdAndStatusIn(inv.getHotelId(), List.of(CommInvoice.Status.OVERDUE)).stream()
                    .anyMatch(o -> !o.getId().equals(inv.getId()));
            plans.findByHotelId(inv.getHotelId()).filter(p -> p.getSuspendedAt() != null && !stillOverdue).ifPresent(p -> {
                p.setSuspendedAt(null);
                p.setSuspendedReason(null);
                plans.save(p);
                log.info("Guest messages resumed for hotel {}: invoice {} paid", inv.getHotelId(), inv.getNumber());
            });
            notifyHotel(inv, "COMM_INVOICE_PAID", "Invoice " + inv.getNumber() + " paid", naira(inv.getTotalAmount()) + " for guest messages in "
                    + monthName(inv.getMonth()) + (stillOverdue ? "" : " · guest messages are on"));
            return inv;
        }));
    }

    // ---- invoicing -------------------------------------------------------------------------------------------------

    /** 00:30 on the 1st (Lagos): last month's invoices. */
    @Scheduled(cron = "${hospio.billing.issue-cron:0 30 0 1 * *}", zone = "Africa/Lagos")
    public void issueLastMonth() {
        issueAll(YearMonth.now(LAGOS).minusMonths(1));
    }

    /** One invoice per hotel with billable messages in {@code month} not yet invoiced. Returns the invoices made. */
    public List<CommInvoice> issueAll(YearMonth month) {
        Instant from = month.atDay(1).atStartOfDay(LAGOS).toInstant();
        Instant to = month.plusMonths(1).atDay(1).atStartOfDay(LAGOS).toInstant();
        List<CommInvoice> out = new ArrayList<>();
        for (Object[] h : asSystem(() -> deliveries.hotelsToBill(CHARGED, from, to))) {
            try {
                issue((String) h[0], (String) h[1], month).ifPresent(out::add);
            } catch (RuntimeException e) {
                log.error("Guest-message invoice for hotel {} ({}) not issued: {}", h[0], month, e.getMessage());
            }
        }
        log.info("Guest-message invoices for {}: {} issued", month, out.size());
        return out;
    }

    /** The invoice of one hotel's {@code month} (empty when nothing billable is left to invoice). */
    public Optional<CommInvoice> issue(String hotelId, String businessId, YearMonth month) {
        Instant from = month.atDay(1).atStartOfDay(LAGOS).toInstant();
        Instant to = month.plusMonths(1).atDay(1).atStartOfDay(LAGOS).toInstant();
        CommInvoice made = asSystem(() -> tx.execute(s -> {
            Usage u = usage(hotelId, month);
            if (u.emailCount() + u.smsCount() + u.whatsappCount() == 0) {
                return null;
            }
            if (invoices.findByHotelIdAndMonth(hotelId, month.toString()).isPresent()) {
                // A second run for the month (late reports): the stragglers wait for next month's invoice.
                return null;
            }
            String number = "MSG-" + month.toString().replace("-", "") + "-" + String.format("%04d", invoices.countByMonth(month.toString()) + 1);
            LocalDate issued = LocalDate.now(LAGOS);
            CommInvoice inv = CommInvoice.builder().businessId(businessId).hotelId(hotelId).number(number).month(month.toString())
                    .hotelName(tenants.hotel(hotelId).map(TenantClient.Sender::name).orElse(null))
                    .emailCount(u.emailCount()).smsCount(u.smsCount()).whatsappCount(u.whatsappCount())
                    .emailAmount(u.emailAmount()).smsAmount(u.smsAmount()).whatsappAmount(u.whatsappAmount()).totalAmount(u.total())
                    .status(CommInvoice.Status.DUE).issuedAt(Instant.now()).dueOn(issued.plusDays(GRACE_DAYS)).build();
            inv = invoices.save(inv);
            deliveries.attachToInvoice(inv.getId(), hotelId, CHARGED, from, to);
            return inv;
        }));
        if (made != null) {
            log.info("Guest-message invoice {} for hotel {}: {}", made.getNumber(), hotelId, made.getTotalAmount());
            tellIssued(made);
        }
        return Optional.ofNullable(made);
    }

    /** 09:00 daily (Lagos): an invoice past its due date is overdue and its hotel's guest messages are suspended. */
    @Scheduled(cron = "${hospio.billing.overdue-cron:0 0 9 * * *}", zone = "Africa/Lagos")
    public int suspendOverdue() {
        List<CommInvoice> late = asSystem(() -> invoices.findByStatusAndDueOnBefore(CommInvoice.Status.DUE, LocalDate.now(LAGOS)));
        for (CommInvoice inv : late) {
            asSystem(() -> tx.execute(s -> {
                inv.setStatus(CommInvoice.Status.OVERDUE);
                invoices.save(inv);
                CommPlan plan = plans.findByHotelId(inv.getHotelId())
                        .orElseGet(() -> CommPlan.builder().businessId(inv.getBusinessId()).hotelId(inv.getHotelId()).build());
                if (plan.getSuspendedAt() == null) {
                    plan.setSuspendedAt(Instant.now());
                    plan.setSuspendedReason("invoice " + inv.getNumber() + " (" + naira(inv.getTotalAmount()) + ") was due on " + inv.getDueOn());
                    plans.save(plan);
                }
                notifyHotel(inv, "COMM_SUSPENDED", "Guest messages suspended", "Invoice " + inv.getNumber() + " (" + naira(inv.getTotalAmount())
                        + ") is overdue. Pay it to turn guest messages back on.");
                return null;
            }));
            log.warn("Guest messages suspended for hotel {}: invoice {} overdue", inv.getHotelId(), inv.getNumber());
        }
        return late.size();
    }

    private void tellIssued(CommInvoice inv) {
        notifyHotel(inv, "COMM_INVOICE", "Guest-message invoice " + inv.getNumber(), naira(inv.getTotalAmount()) + " for " + monthName(inv.getMonth())
                + " · due " + inv.getDueOn());
        String link = hotelAppUrl + "/dashboard/hotel-setup/guest-messages?invoice=" + inv.getId();
        String text = "Hello,\n\nYour guest-message invoice for " + monthName(inv.getMonth()) + " is ready.\n\nInvoice: " + inv.getNumber()
                + "\nHotel: " + nz(inv.getHotelName()) + "\nEmails: " + inv.getEmailCount() + " (" + naira(inv.getEmailAmount()) + ")"
                + "\nSMS: " + inv.getSmsCount() + " (" + naira(inv.getSmsAmount()) + ")"
                + "\nWhatsApp: " + inv.getWhatsappCount() + " (" + naira(inv.getWhatsappAmount()) + ")"
                + "\nTotal: " + naira(inv.getTotalAmount()) + "\nDue by: " + inv.getDueOn()
                + "\n\nIf it is not paid by then, guest messages are paused until it is.\n\nPay or see every message: " + link;
        List<String> to = new ArrayList<>();
        tenants.hotel(inv.getHotelId()).map(TenantClient.Sender::email).ifPresent(to::add);
        tenants.business(inv.getBusinessId()).map(TenantClient.Sender::email).filter(e -> !to.contains(e)).ifPresent(to::add);
        for (String email : to) {
            mail.accept(DeliveryRequestedEvent.builder().eventId(UUID.nameUUIDFromBytes(("INVOICE-" + inv.getId() + "-" + email).getBytes()).toString())
                    .occurredAt(Instant.now()).businessId(inv.getBusinessId()).channel("EMAIL").to(email)
                    .subject("Your guest-message invoice " + inv.getNumber() + " · " + monthName(inv.getMonth())).text(text).kind("INVOICE")
                    .ref(inv.getId()).fromName("Hospio360").build());
        }
    }

    private void notifyHotel(CommInvoice inv, String kind, String title, String body) {
        try {
            TenantContext.runWith(Tenant.systemFor(inv.getBusinessId(), inv.getHotelId(), null, "system:billing"), () ->
                    notifications.notifyRoles(kind + ":" + inv.getId(), kind, title, body, "/hotel-setup/guest-messages?invoice=" + inv.getId(),
                            "OWNER", "MANAGER"));
        } catch (RuntimeException e) {
            log.warn("Could not notify hotel {} about invoice {}: {}", inv.getHotelId(), inv.getNumber(), e.getMessage());
        }
    }

    // ---- Hospio360's side --------------------------------------------------------------------------------------------

    public Prices setDefaults(BigDecimal email, BigDecimal sms, BigDecimal whatsapp) {
        HospioUserDetails me = CurrentUser.require();
        CommPrices p = prices.findById("DEFAULT").orElseGet(() -> {
            CommPrices n = new CommPrices();
            n.setId("DEFAULT");
            return n;
        });
        p.setEmailPrice(price(email, "email"));
        p.setSmsPrice(price(sms, "SMS"));
        p.setWhatsappPrice(price(whatsapp, "WhatsApp"));
        p.setUpdatedBy(me.getFullName());
        p.setUpdatedAt(Instant.now());
        prices.save(p);
        return defaults();
    }

    /** Hospio360 sets a hotel's service: on or off and its own prices (null = the default). */
    public PlanView setPlan(String businessId, String hotelId, Boolean enabled, BigDecimal email, BigDecimal sms, BigDecimal whatsapp, boolean clearPrices) {
        HospioUserDetails me = CurrentUser.require();
        CommPlan plan = asSystem(() -> plans.findByHotelId(hotelId)).orElseGet(() -> CommPlan.builder().businessId(businessId).hotelId(hotelId).build());
        if (plan.getBusinessId() == null) {
            plan.setBusinessId(businessId);
        }
        if (enabled != null) {
            plan.setEnabled(enabled);
        }
        if (clearPrices) {
            plan.setEmailPrice(null);
            plan.setSmsPrice(null);
            plan.setWhatsappPrice(null);
        } else {
            if (email != null) {
                plan.setEmailPrice(price(email, "email"));
            }
            if (sms != null) {
                plan.setSmsPrice(price(sms, "SMS"));
            }
            if (whatsapp != null) {
                plan.setWhatsappPrice(price(whatsapp, "WhatsApp"));
            }
        }
        plan.setChangedBy(me.getFullName() + " (Hospio360)");
        plan.setChangedAt(Instant.now());
        asSystem(() -> plans.save(plan));
        return asSystem(() -> plan(hotelId));
    }

    public PlanView setSuspended(String hotelId, boolean suspended, String reason) {
        CommPlan plan = asSystem(() -> plans.findByHotelId(hotelId)).orElseThrow(() -> new ResourceNotFoundException("This hotel has no guest-message plan"));
        plan.setSuspendedAt(suspended ? Instant.now() : null);
        plan.setSuspendedReason(suspended ? (StringUtils.hasText(reason) ? reason.trim() : "suspended by Hospio360") : null);
        asSystem(() -> plans.save(plan));
        return asSystem(() -> plan(hotelId));
    }

    public List<PlanView> plansOf(String businessId, List<String> hotelIds) {
        List<PlanView> out = new ArrayList<>();
        for (String h : hotelIds) {
            out.add(asSystem(() -> plan(h)));
        }
        return out;
    }

    public Page<CommInvoice> allInvoices(String status, String month, String hotelId, int page, int size) {
        return asSystem(() -> invoices.findAll((root, q, cb) -> {
            List<jakarta.persistence.criteria.Predicate> w = new ArrayList<>();
            if (StringUtils.hasText(status)) {
                w.add(cb.equal(root.get("status"), CommInvoice.Status.valueOf(status.trim().toUpperCase(Locale.ROOT))));
            }
            if (StringUtils.hasText(month)) {
                w.add(cb.equal(root.get("month"), month.trim()));
            }
            if (StringUtils.hasText(hotelId)) {
                w.add(cb.equal(root.get("hotelId"), hotelId.trim()));
            }
            return cb.and(w.toArray(new jakarta.persistence.criteria.Predicate[0]));
        }, PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "month").and(Sort.by("number")))));
    }

    // ---- helpers ---------------------------------------------------------------------------------------------------

    private static BigDecimal price(BigDecimal v, String what) {
        if (v == null || v.signum() < 0 || v.compareTo(new BigDecimal("10000")) > 0) {
            throw new BadRequestException("The " + what + " price must be between ₦0 and ₦10,000 a message");
        }
        return v.setScale(2, RoundingMode.HALF_UP);
    }

    private static int index(String via) {
        return "SMS".equalsIgnoreCase(via) ? 1 : "WHATSAPP".equalsIgnoreCase(via) ? 2 : 0;
    }

    private static BigDecimal money(Object v) {
        return v == null ? BigDecimal.ZERO : new BigDecimal(v.toString()).setScale(2, RoundingMode.HALF_UP);
    }

    public static String naira(BigDecimal v) {
        java.text.NumberFormat f = java.text.NumberFormat.getNumberInstance(Locale.ENGLISH);
        BigDecimal x = v == null ? BigDecimal.ZERO : v;
        f.setMinimumFractionDigits(x.stripTrailingZeros().scale() > 0 ? 2 : 0);
        f.setMaximumFractionDigits(2);
        return "₦" + f.format(x);
    }

    public static String monthName(String yyyyMm) {
        YearMonth m = YearMonth.parse(yyyyMm);
        return m.getMonth().getDisplayName(java.time.format.TextStyle.FULL, Locale.ENGLISH) + " " + m.getYear();
    }

    /** What a kind of message is called on an invoice. */
    public static String label(String kind) {
        return switch (kind == null ? "" : kind) {
            case "BOOKING_CONFIRMATION" -> "Booking confirmations";
            case "BOOKING_CHANGED" -> "Booking date changes";
            case "BOOKING_CANCELLED" -> "Booking cancellations";
            case "RECEIPT" -> "Payment receipts";
            case "FOLIO" -> "Folios";
            case "STAY_SUMMARY" -> "Stay summaries";
            case "MESSAGE" -> "Messages to guests";
            case "STATEMENT" -> "Company statements";
            case "PAYMENT_REMINDER" -> "Payment reminders";
            case "PURCHASE_ORDER" -> "Purchase orders";
            default -> kind;
        };
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static <T> T asSystem(java.util.function.Supplier<T> work) {
        if (TenantContext.current().map(Tenant::isPlatform).orElse(false)) {
            return work.get();
        }
        return TenantContext.runWith(Tenant.system("billing"), work);
    }
}
