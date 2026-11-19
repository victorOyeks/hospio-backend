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
 * Payment receipts go to the guest on WhatsApp only (the owner's call, 19 Nov 2026: no printed receipts, no emailed payment
 * receipts): the approved {@code hospio_payment_receipt_v2} template to the guest's phone. Two ways in: Send on WhatsApp right after
 * taking a payment, or from My payments, a sale or a split bill's part ({@link #send}, every desk that takes payments), and Send
 * receipt again for managers and finance ({@link #resend}). A print or email channel from an older screen is refused in words. The
 * delivery is published like every other one (messaging-service records it, and staging's allow-list holds back any number not on
 * it). The receipt keeps who it was last sent to and how. Emailing a receipt or a folio ({@link #email}) stays for other callers.
 */
@Service
@RequiredArgsConstructor
public class ReceiptService {

    private final PaymentRequestRepository payments;
    private final ReceiptRepository receipts;
    private final DeliveryService deliveries;

    /**
     * The receipt handed to messaging-service, and what became of it as far as is known now (docs/whatsapp-receipts.md, "What the desk is
     * told"): {@code outcome} QUEUED, SKIPPED, HELD_BACK, NOT_CONNECTED or UNKNOWN with messaging's {@code reason}, whether the hotel's
     * guest messages are on, and {@code message}, the words the desk sees.
     */
    public record Resent(String receiptNumber, String sentTo, String sentVia, List<Delivery> deliveries, String outcome, String reason,
                         Boolean guestMessagesOn, String message) {
    }

    /** Asks messaging-service what becomes of a receipt (absent in unit tests: UNKNOWN). */
    private com.hospio.payment.client.MessagingClient messaging;

    /** Hotel setup → Guest messaging "Send receipts automatically" (absent in unit tests: on). */
    private com.hospio.payment.client.HotelConfigClient hotelConfig;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setMessaging(com.hospio.payment.client.MessagingClient messaging) {
        this.messaging = messaging;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setHotelConfig(com.hospio.payment.client.HotelConfigClient hotelConfig) {
        this.hotelConfig = hotelConfig;
    }

    /** What a receipt on WhatsApp to {@code phone} at the caller's hotel would come to (no phone: whether guest messages are on). */
    public com.hospio.payment.dto.ReceiptOutlook outlook(String phone) {
        String hotel = TenantContext.requireHotel();
        String business = TenantContext.current().map(t -> t.businessId()).orElse(null);
        return messaging == null ? com.hospio.payment.dto.ReceiptOutlook.unknown() : messaging.receiptOutlook(hotel, business, phone);
    }

    /**
     * The automatic receipt of a payment just taken (POST /payments with receiptPreference WHATSAPP; messaging-service sends it once the
     * payment is approved): null when the desk chose Don't send. Never throws: a payment never fails over its receipt.
     */
    public com.hospio.payment.dto.ReceiptOutlook automatic(String paymentId) {
        try {
            PaymentRequest p = payments.findById(paymentId).orElse(null);
            if (p == null || !"WHATSAPP".equalsIgnoreCase(p.getReceiptPreference())) {
                return null;
            }
            if (hotelConfig != null && p.getHotelId() != null && !hotelConfig.sendsReceipts(p.getHotelId())) {
                return new com.hospio.payment.dto.ReceiptOutlook(com.hospio.payment.dto.ReceiptOutlook.SKIPPED, AUTO_OFF, null);
            }
            if (blank(p.getGuestPhone())) {
                return com.hospio.payment.dto.ReceiptOutlook.noPhone(null);
            }
            String business = TenantContext.current().map(t -> t.businessId()).orElse(null);
            return messaging == null ? com.hospio.payment.dto.ReceiptOutlook.unknown() : messaging.receiptOutlook(p.getHotelId(), business, p.getGuestPhone());
        } catch (RuntimeException e) {
            return com.hospio.payment.dto.ReceiptOutlook.unknown();
        }
    }

    static final String AUTO_OFF = "Automatic receipts are off (Hotel setup → Guest messaging)";

    /** No phone on the payment and none typed: nothing is sent. */
    static final String NO_PHONE = "Receipt not sent: no phone number · type the guest's WhatsApp number to send it";

    /** The words an older screen gets when it asks for a printed or emailed payment receipt. */
    static final String WHATSAPP_ONLY = "Receipts go to the guest on WhatsApp only: pick WhatsApp or Don't send";

    /** Send receipt again (managers and finance): on WhatsApp to the phone the payment has. */
    @Transactional
    public Resent resend(String paymentId) {
        return send(new ReceiptViews.SendRequest(paymentId, null, null));
    }

    /**
     * The receipt of an approved payment on WhatsApp: to {@code phone} when given (the guest's number as the desk typed it, kept on
     * the payment when it had none), else to the phone the payment has. {@code channel} is WHATSAPP (or left out); PRINT, EMAIL and
     * anything else are refused in words.
     */
    @Transactional
    public Resent send(ReceiptViews.SendRequest request) {
        if (request == null || blank(request.paymentId())) {
            throw new BadRequestException("Say which payment's receipt to send");
        }
        checkChannel(request.channel());
        PaymentRequest p = payments.findById(request.paymentId()).orElseThrow(() -> new ResourceNotFoundException("Payment not found"));
        if (p.getHotelId() != null && !p.getHotelId().equals(TenantContext.requireHotel())) {
            throw new ResourceNotFoundException("Payment not found");
        }
        if (p.getStatus() != PaymentStatus.APPROVED) {
            throw new BadRequestException("Only an approved payment has a receipt");
        }
        String phone = blank(request.phone()) ? p.getGuestPhone() : com.hospio.common.util.Phones.clean(request.phone(), "The guest's WhatsApp number");
        Receipt receipt = receipts.findFirstByPaymentIdOrderByCreatedAtAsc(p.getId())
                .orElseThrow(() -> new BadRequestException("This payment has no receipt yet"));
        String text = "Receipt " + receipt.getNumber() + ". Thank you" + (blank(p.getGuestName()) ? "" : " " + p.getGuestName())
                + ". We received " + naira(p.getAmount()) + " by " + p.getMethod().name().toLowerCase()
                + (blank(p.getDescription()) ? "" : " for " + p.getDescription())
                + (blank(p.getProviderRef()) ? "" : ". Ref " + p.getProviderRef()) + ".";
        String method = p.getMethod().name().toLowerCase(java.util.Locale.ROOT);
        String first = blank(p.getGuestName()) ? "there" : p.getGuestName().trim().split("\\s+")[0];
        Delivery delivery = whatsAppDelivery(phone, receipt.getNumber(), text,
                List.of(first, "@hotel", naira(p.getAmount()), Character.toUpperCase(method.charAt(0)) + method.substring(1),
                        blank(p.getProviderRef()) ? receipt.getNumber() : p.getProviderRef()));
        if (blank(p.getGuestPhone())) {
            p.setGuestPhone(delivery.to());
            payments.save(p);
        }
        // Asked before it is handed over, so the desk is told the truth: skipped (guest messages off), held back (staging's allow-list).
        com.hospio.payment.dto.ReceiptOutlook outlook = messaging == null ? com.hospio.payment.dto.ReceiptOutlook.unknown()
                : messaging.receiptOutlook(p.getHotelId(), TenantContext.current().map(t -> t.businessId()).orElse(null), delivery.to());
        // Handed over whatever the outlook: messaging-service records it (SKIPPED with the reason), so the deliveries show it.
        List<Delivery> echoed = deliveries.send(delivery);
        if (outlook.sent() || com.hospio.payment.dto.ReceiptOutlook.HELD_BACK.equals(outlook.outcome())) {
            receipt.setSentTo(delivery.to());
            receipt.setSentVia(delivery.channel().name());
            receipts.save(receipt);
        }
        return resent(receipt.getNumber(), delivery.to(), delivery.channel().name(), echoed, outlook);
    }

    static Resent resent(String number, String to, String via, List<Delivery> echoed, com.hospio.payment.dto.ReceiptOutlook outlook) {
        return new Resent(number, to, via, echoed, outlook.outcome(), outlook.reason(), outlook.guestMessagesOn(), outlook.message(number, to));
    }

    /** WHATSAPP or nothing; a printed or emailed payment receipt (older screens) is refused in words. */
    static void checkChannel(String channel) {
        if (blank(channel) || "WHATSAPP".equalsIgnoreCase(channel.trim())) {
            return;
        }
        if ("NONE".equalsIgnoreCase(channel.trim()) || "NEVER".equalsIgnoreCase(channel.trim())) {
            throw new BadRequestException("Nothing to send: Don't send means no receipt. Pick WhatsApp to send one");
        }
        throw new BadRequestException(WHATSAPP_ONLY);
    }

    /**
     * The receipt on WhatsApp as the approved {@code hospio_payment_receipt_v2} template ({@code values}: first name, @hotel, amount,
     * method, reference). No phone: refused in words.
     */
    static Delivery whatsAppDelivery(String phone, String number, String text, List<String> values) {
        if (blank(phone)) {
            throw new BadRequestException(NO_PHONE);
        }
        return Delivery.whatsApp(phone.trim(), "Receipt", text, Delivery.Kind.RECEIPT, number)
                .withTemplate(com.hospio.common.delivery.WhatsAppTemplates.RECEIPT, values);
    }

    // ---- receipts to view (and email, for other callers) ---------------------------------------------------------------

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
