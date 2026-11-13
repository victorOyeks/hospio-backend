package com.hospio.payment.service;

import com.hospio.common.delivery.Delivery;
import com.hospio.common.delivery.DeliveryService;
import com.hospio.common.enums.PaymentStatus;
import com.hospio.common.exception.BadRequestException;
import com.hospio.common.exception.ResourceNotFoundException;
import com.hospio.common.tenancy.TenantContext;
import com.hospio.payment.entity.PaymentRequest;
import com.hospio.payment.entity.Receipt;
import com.hospio.payment.repository.PaymentRequestRepository;
import com.hospio.payment.repository.ReceiptRepository;
import java.math.BigDecimal;
import java.text.NumberFormat;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Resend receipt (Manager · Payments, My payments): the receipt of an approved payment goes to the guest again by email
 * (WhatsApp and SMS are coming soon). The delivery is published like every other one; on
 * staging (deliveries echo on) it comes back in the response so the screen can show what would have gone out.
 * The receipt keeps who it was last sent to and how.
 */
@Service
@RequiredArgsConstructor
public class ReceiptService {

    private final PaymentRequestRepository payments;
    private final ReceiptRepository receipts;
    private final DeliveryService deliveries;

    public record Resent(String receiptNumber, String sentTo, String sentVia, List<Delivery> deliveries) {
    }

    @Transactional
    public Resent resend(String paymentId) {
        PaymentRequest p = payments.findById(paymentId).orElseThrow(() -> new ResourceNotFoundException("Payment not found"));
        if (p.getHotelId() != null && !p.getHotelId().equals(TenantContext.requireHotel())) {
            throw new ResourceNotFoundException("Payment not found");
        }
        if (p.getStatus() != PaymentStatus.APPROVED) {
            throw new BadRequestException("Only an approved payment has a receipt");
        }
        Receipt receipt = receipts.findFirstByPaymentIdOrderByCreatedAtAsc(p.getId())
                .orElseThrow(() -> new BadRequestException("This payment has no receipt yet"));
        String text = "Receipt " + receipt.getNumber() + ". Thank you" + (blank(p.getGuestName()) ? "" : " " + p.getGuestName())
                + ". We received " + naira(p.getAmount()) + " by " + p.getMethod().name().toLowerCase()
                + (blank(p.getDescription()) ? "" : " for " + p.getDescription())
                + (blank(p.getProviderRef()) ? "" : ". Ref " + p.getProviderRef()) + ".";
        Delivery delivery = resendDelivery(p.getGuestEmail(), p.getGuestPhone(), receipt.getNumber(), text);
        List<Delivery> echoed = deliveries.send(delivery);
        receipt.setSentTo(delivery.to());
        receipt.setSentVia(delivery.channel().name());
        receipts.save(receipt);
        return new Resent(receipt.getNumber(), delivery.to(), delivery.channel().name(), echoed);
    }

    /**
     * The resend goes by email, the one channel that is sent for real (docs/email.md). Without an email it is refused in words:
     * WhatsApp and SMS receipts are coming soon, so the desk prints it or emails it to an address the guest gives.
     */
    static Delivery resendDelivery(String email, String phone, String number, String text) {
        if (!blank(email)) {
            return Delivery.email(email.trim(), "Your receipt " + number, text, Delivery.Kind.RECEIPT, number);
        }
        throw new BadRequestException(!blank(phone)
                ? "This guest has no email · print the receipt or email it from the receipt page (WhatsApp receipts are coming soon)"
                : "This payment has no guest email · print the receipt or email it from the receipt page");
    }

    // ---- printable and emailed receipts ---------------------------------------------------------------

    /** One payment's receipt, for the receipt page. The payment must be approved and belong to the caller's hotel. */
    @Transactional(readOnly = true)
    public ReceiptViews.ReceiptView view(String paymentId) {
        PaymentRequest p = payments.findById(paymentId).orElseThrow(() -> new ResourceNotFoundException("Payment not found"));
        sameHotel(p);
        if (p.getStatus() != PaymentStatus.APPROVED) {
            throw new BadRequestException("Only an approved payment has a receipt");
        }
        Receipt r = receipts.findFirstByPaymentIdOrderByCreatedAtAsc(p.getId()).orElseThrow(() -> new BadRequestException("This payment has no receipt yet"));
        return ReceiptViews.ReceiptView.of(p, r);
    }

    /** Every receipt of a bar or kitchen sale (orderId) or a stay's folio (folioId), oldest first. */
    @Transactional(readOnly = true)
    public List<ReceiptViews.ReceiptView> forRef(String orderId, String folioId) {
        if (blank(orderId) == blank(folioId)) {
            throw new BadRequestException("Say whose receipts: a sale (orderId) or a folio (folioId)");
        }
        List<PaymentRequest> list = !blank(orderId) ? payments.findByOrderIdAndStatusOrderByCreatedAtAsc(orderId, PaymentStatus.APPROVED)
                : payments.findByFolioIdAndStatusOrderByCreatedAtAsc(folioId, PaymentStatus.APPROVED);
        String hotel = com.hospio.common.tenancy.TenantContext.current().map(t -> t.hotelId()).orElse(null);
        List<PaymentRequest> mine = list.stream().filter(p -> hotel == null || hotel.equals(p.getHotelId())).toList();
        Map<String, Receipt> byPayment = receiptsOf(mine.stream().map(PaymentRequest::getId).toList());
        return mine.stream().filter(p -> byPayment.containsKey(p.getId())).map(p -> ReceiptViews.ReceiptView.of(p, byPayment.get(p.getId()))).toList();
    }

    /** Email receipt: the receipt (one payment, a sale's or a folio's) goes to the address given, as the screen printed it. */
    @Transactional
    public ReceiptViews.Emailed email(ReceiptViews.EmailRequest request) {
        ReceiptViews.exactlyOne(request);
        String to = ReceiptViews.checkEmail(request.to());
        List<ReceiptViews.ReceiptView> views = !blank(request.paymentId()) ? List.of(view(request.paymentId()))
                : forRef(request.orderId(), request.folioId());
        if (views.isEmpty()) {
            throw new BadRequestException(!blank(request.orderId()) ? "This sale has no paid receipt yet" : "This folio has no payment with a receipt yet");
        }
        ReceiptViews.ReceiptView first = views.get(0);
        String text = ReceiptViews.text(request.text(), views, first.guestName());
        String numbers = String.join(",", views.stream().map(ReceiptViews.ReceiptView::number).toList());
        List<Delivery> echoed = deliveries.send(Delivery.email(to, ReceiptViews.subject(views), text,
                !blank(request.folioId()) ? Delivery.Kind.FOLIO : Delivery.Kind.RECEIPT, numbers.length() > 100 ? first.number() : numbers));
        for (Receipt r : receiptsOf(views.stream().map(ReceiptViews.ReceiptView::paymentId).toList()).values()) {
            r.setSentTo(to);
            r.setSentVia("EMAIL");
            receipts.save(r);
        }
        return new ReceiptViews.Emailed(views.stream().map(ReceiptViews.ReceiptView::number).toList(), to, echoed);
    }

    /** Each payment's receipt in one query; a payment with two (from before receipts.payment_id was unique) keeps its first. */
    private Map<String, Receipt> receiptsOf(List<String> paymentIds) {
        Map<String, Receipt> out = new HashMap<>();
        if (paymentIds.isEmpty()) {
            return out;
        }
        for (Receipt r : receipts.findByPaymentIdIn(paymentIds)) {
            out.merge(r.getPaymentId(), r, (a, b) -> b.getCreatedAt() != null && a.getCreatedAt() != null && b.getCreatedAt().isBefore(a.getCreatedAt()) ? b : a);
        }
        return out;
    }

    private static void sameHotel(PaymentRequest p) {
        String hotel = com.hospio.common.tenancy.TenantContext.current().map(t -> t.hotelId()).orElse(null);
        if (hotel != null && p.getHotelId() != null && !p.getHotelId().equals(hotel)) {
            throw new ResourceNotFoundException("Payment not found");
        }
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static String naira(BigDecimal amount) {
        NumberFormat f = NumberFormat.getNumberInstance(Locale.US);
        f.setMaximumFractionDigits(2);
        return "₦" + f.format(amount);
    }
}
