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
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Resend receipt (Manager · Payments): the receipt of an approved payment goes to the guest again, by WhatsApp
 * when the payment carries a phone, otherwise by email. The delivery is published like every other one; on
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
        Receipt receipt = receipts.findByPaymentId(p.getId())
                .orElseThrow(() -> new BadRequestException("This payment has no receipt yet"));
        String text = "Receipt " + receipt.getNumber() + ". Thank you" + (blank(p.getGuestName()) ? "" : " " + p.getGuestName())
                + ". We received " + naira(p.getAmount()) + " by " + p.getMethod().name().toLowerCase()
                + (blank(p.getDescription()) ? "" : " for " + p.getDescription())
                + (blank(p.getProviderRef()) ? "" : ". Ref " + p.getProviderRef()) + ".";
        Delivery delivery;
        if (!blank(p.getGuestPhone())) {
            delivery = new Delivery(Delivery.Channel.WHATSAPP, p.getGuestPhone(), "Your receipt", text, Delivery.Kind.RECEIPT, receipt.getNumber());
        } else if (!blank(p.getGuestEmail())) {
            delivery = Delivery.email(p.getGuestEmail(), "Your receipt " + receipt.getNumber(), text, Delivery.Kind.RECEIPT, receipt.getNumber());
        } else {
            throw new BadRequestException("This payment has no guest phone or email to send the receipt to");
        }
        List<Delivery> echoed = deliveries.send(delivery);
        receipt.setSentTo(delivery.to());
        receipt.setSentVia(delivery.channel().name());
        receipts.save(receipt);
        return new Resent(receipt.getNumber(), delivery.to(), delivery.channel().name(), echoed);
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
        Receipt r = receipts.findByPaymentId(p.getId()).orElseThrow(() -> new BadRequestException("This payment has no receipt yet"));
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
        return list.stream().filter(p -> hotel == null || hotel.equals(p.getHotelId()))
                .flatMap(p -> receipts.findByPaymentId(p.getId()).map(r -> ReceiptViews.ReceiptView.of(p, r)).stream()).toList();
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
        for (ReceiptViews.ReceiptView v : views) {
            receipts.findByPaymentId(v.paymentId()).ifPresent(r -> {
                r.setSentTo(to);
                r.setSentVia("EMAIL");
                receipts.save(r);
            });
        }
        return new ReceiptViews.Emailed(views.stream().map(ReceiptViews.ReceiptView::number).toList(), to, echoed);
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
