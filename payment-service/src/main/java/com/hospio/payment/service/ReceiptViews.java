package com.hospio.payment.service;

import com.hospio.common.delivery.Delivery;
import com.hospio.common.exception.BadRequestException;
import com.hospio.payment.entity.PaymentRequest;
import com.hospio.payment.entity.Receipt;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.regex.Pattern;

/** What a printed or emailed receipt shows for one approved payment (docs/manager-leftovers.md · Receipts). */
public final class ReceiptViews {

    private ReceiptViews() {
    }

    private static final Pattern EMAIL = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");
    /** The longest receipt text the email takes (the printed receipt rendered as lines). */
    public static final int MAX_TEXT = 6000;

    public record ReceiptView(String number, Instant issuedAt, String paymentId, BigDecimal amount, String currency, String method,
                              String reference, String takenBy, String role, String guestName, String guestPhone, String guestEmail,
                              String description, String orderId, String folioId, String bookingId, String hotelId, Instant paidAt,
                              String sentTo, String sentVia) {
        static ReceiptView of(PaymentRequest p, Receipt r) {
            return new ReceiptView(r.getNumber(), r.getCreatedAt(), p.getId(), p.getAmount(), p.getCurrency(), p.getMethod().name(),
                    p.getProviderRef(), p.getRequestedByName(), p.getRole() == null ? null : p.getRole().name(), p.getGuestName(), p.getGuestPhone(),
                    p.getGuestEmail(), p.getDescription(), p.getOrderId(), p.getFolioId(), p.getBookingId(), p.getHotelId(),
                    p.getResolvedAt() == null ? p.getCreatedAt() : p.getResolvedAt(), r.getSentTo(), r.getSentVia());
        }
    }

    /** Email a receipt: one of paymentId, orderId (a bar or kitchen sale) or folioId (a stay at check-out), to an address. */
    public record EmailRequest(String paymentId, String orderId, String folioId, String to, String text) {
    }

    public record Emailed(List<String> receiptNumbers, String to, List<Delivery> deliveries) {
    }

    static String checkEmail(String to) {
        String t = to == null ? "" : to.trim();
        if (t.isEmpty()) {
            throw new BadRequestException("Give the email address to send the receipt to");
        }
        if (!EMAIL.matcher(t).matches() || t.length() > 254) {
            throw new BadRequestException(t + " is not an email address");
        }
        return t;
    }

    static void exactlyOne(EmailRequest r) {
        int n = (blank(r.paymentId()) ? 0 : 1) + (blank(r.orderId()) ? 0 : 1) + (blank(r.folioId()) ? 0 : 1);
        if (n != 1) {
            throw new BadRequestException("Say which receipt: a payment, a sale or a folio");
        }
    }

    /** The receipt's text: what the screen printed when it sent one (trimmed to {@link #MAX_TEXT}), else one line per payment. */
    static String text(String given, List<ReceiptView> views, String guestName) {
        if (!blank(given)) {
            String t = given.strip();
            return t.length() > MAX_TEXT ? t.substring(0, MAX_TEXT) : t;
        }
        StringBuilder b = new StringBuilder("Thank you" + (blank(guestName) ? "" : " " + guestName) + ".\n\n");
        for (ReceiptView v : views) {
            b.append("Receipt ").append(v.number()).append(": ").append(naira(v.amount())).append(" by ").append(v.method().toLowerCase())
                    .append(blank(v.description()) ? "" : " for " + v.description()).append(blank(v.reference()) ? "" : " · ref " + v.reference())
                    .append('\n');
        }
        return b.toString();
    }

    static String subject(List<ReceiptView> views) {
        return views.size() == 1 ? "Your receipt " + views.get(0).number()
                : "Your receipts " + views.get(0).number() + " to " + views.get(views.size() - 1).number();
    }

    static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    static String naira(BigDecimal amount) {
        java.text.NumberFormat f = java.text.NumberFormat.getNumberInstance(java.util.Locale.US);
        f.setMinimumFractionDigits(2);
        f.setMaximumFractionDigits(2);
        return "₦" + f.format(amount == null ? BigDecimal.ZERO : amount);
    }
}
