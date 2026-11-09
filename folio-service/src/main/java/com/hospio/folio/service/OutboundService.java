package com.hospio.folio.service;

import com.hospio.common.delivery.Delivery;
import com.hospio.common.delivery.DeliveryService;
import com.hospio.common.exception.BadRequestException;
import com.hospio.folio.entity.Invoice;
import com.hospio.folio.entity.Organization;
import java.math.BigDecimal;
import java.text.NumberFormat;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * What folio-service sends to people: a guest's folio (stay summary) by email or WhatsApp, and an organization's
 * statement or payment reminder to its contact. Each is a delivery published for messaging-service; on staging
 * (deliveries echo on) the same deliveries come back in the response so the screen can show what went out.
 */
@Service
@RequiredArgsConstructor
public class OutboundService {

    private final FolioService folios;
    private final OrganizationService organizations;
    private final DeliveryService deliveries;

    public record Sent(String to, String channel, List<Delivery> deliveries) {
    }

    /** Sends the stay summary to the guest. {@code channel} is EMAIL or WHATSAPP; {@code to} defaults to the folio's contact. */
    @Transactional(readOnly = true)
    public Sent sendFolio(String folioId, String channel, String to) {
        var f = folios.find(folioId);
        boolean email = "EMAIL".equalsIgnoreCase(channel);
        String target = blank(to) ? (email ? f.getGuestEmail() : f.getGuestPhone()) : to.trim();
        if (blank(target)) {
            throw new BadRequestException(email ? "Add the guest's email to send the folio" : "Add the guest's WhatsApp number to send the folio");
        }
        String text = folios.staySummary(folioId);
        Delivery d = email ? Delivery.email(target, "Your folio · " + f.getGuestName(), text, Delivery.Kind.FOLIO, f.getId())
                : new Delivery(Delivery.Channel.WHATSAPP, target, "Your folio", text, Delivery.Kind.FOLIO, f.getId());
        return new Sent(target, d.channel().name(), deliveries.send(d));
    }

    /** A statement: every invoice on the account, what was paid and what is still owed. */
    @Transactional(readOnly = true)
    public Sent statement(String orgId) {
        Organization org = organizations.get(orgId);
        List<Invoice> invoices = organizations.invoices(orgId);
        StringBuilder sb = new StringBuilder("Statement for ").append(org.getName()).append(" · ").append(LocalDate.now()).append("\n\n");
        if (invoices.isEmpty()) {
            sb.append("No invoices yet.\n");
        }
        for (Invoice i : invoices) {
            sb.append(i.getNumber()).append(" · ").append(i.getPeriodFrom()).append(" to ").append(i.getPeriodTo())
                    .append(" · ").append(naira(i.getAmount())).append(" · paid ").append(naira(i.getPaidAmount()))
                    .append(" · due ").append(i.getDueDate()).append(" · ").append(i.getStatus()).append('\n');
        }
        sb.append("\nStill owed on stays: ").append(naira(organizations.outstanding(orgId)));
        return toContact(org, "Statement · " + org.getName(), sb.toString(), Delivery.Kind.STATEMENT);
    }

    /** A reminder of what is still owed, with the invoices not yet paid. */
    @Transactional(readOnly = true)
    public Sent reminder(String orgId) {
        Organization org = organizations.get(orgId);
        BigDecimal owed = organizations.outstanding(orgId);
        if (owed.signum() <= 0) {
            throw new BadRequestException(org.getName() + " owes nothing right now");
        }
        StringBuilder sb = new StringBuilder("Hello").append(blank(org.getContactName()) ? "" : " " + org.getContactName())
                .append(", a reminder that ").append(org.getName()).append(" has ").append(naira(owed)).append(" outstanding.");
        for (Invoice i : organizations.invoices(orgId)) {
            if (i.getStatus() != Invoice.Status.PAID) {
                sb.append("\n").append(i.getNumber()).append(" · ").append(naira(i.getAmount().subtract(i.getPaidAmount()))).append(" due ").append(i.getDueDate());
            }
        }
        return toContact(org, "Payment reminder · " + org.getName(), sb.toString(), Delivery.Kind.PAYMENT_REMINDER);
    }

    private Sent toContact(Organization org, String subject, String text, Delivery.Kind kind) {
        Delivery d;
        if (!blank(org.getContactEmail())) {
            d = Delivery.email(org.getContactEmail(), subject, text, kind, org.getId());
        } else if (!blank(org.getContactPhone())) {
            d = Delivery.sms(org.getContactPhone(), text, kind, org.getId());
        } else {
            throw new BadRequestException("Add a contact email or phone for " + org.getName() + " first");
        }
        return new Sent(d.to(), d.channel().name(), deliveries.send(d));
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static String naira(BigDecimal amount) {
        NumberFormat f = NumberFormat.getNumberInstance(Locale.US);
        f.setMinimumFractionDigits(0);
        f.setMaximumFractionDigits(2);
        return "₦" + f.format(amount == null ? BigDecimal.ZERO : amount);
    }
}
