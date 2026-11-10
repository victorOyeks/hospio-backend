package com.hospio.payment.service;

import com.hospio.common.enums.PaymentMethod;
import com.hospio.common.enums.PaymentStatus;
import com.hospio.common.enums.Role;
import com.hospio.common.exception.BadRequestException;
import com.hospio.common.exception.ResourceNotFoundException;
import com.hospio.common.integrations.PosProvider;
import com.hospio.common.kafka.EventPublisher;
import com.hospio.common.kafka.KafkaTopics;
import com.hospio.common.kafka.events.PaymentApprovedEvent;
import com.hospio.common.kafka.events.PaymentDeclinedEvent;
import com.hospio.common.kafka.events.PaymentRequestedEvent;
import com.hospio.common.security.CurrentUser;
import com.hospio.common.security.HospioUserDetails;
import com.hospio.common.tenancy.Tenant;
import com.hospio.common.tenancy.TenantContext;
import com.hospio.common.util.Money;
import com.hospio.payment.client.HotelConfigClient;
import com.hospio.payment.client.ReservationClient;
import com.hospio.payment.dto.PaymentRequestDto;
import com.hospio.payment.dto.PaymentResponse;
import com.hospio.payment.entity.PaymentRequest;
import com.hospio.payment.entity.PosTerminal;
import com.hospio.payment.entity.Receipt;
import com.hospio.payment.repository.PaymentRequestRepository;
import com.hospio.payment.repository.ReceiptRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The POS model (handoff card 10). Ask card, transfer or cash → a card goes to the role's POS and waits
 * for the provider's answer; cash and a transfer the desk has confirmed are approved on the spot. Verify settles a pending one.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentService {

    private static final Set<Role> PAYING_ROLES = Set.of(Role.FRONT_OFFICE, Role.BAR, Role.KITCHEN, Role.LAUNDRY, Role.MANAGER, Role.OWNER);
    /** Which desk a payment is taken for when the caller holds several roles and doesn't say: the same answer every time. */
    static final List<Role> ROLE_PREFERENCE = List.of(Role.FRONT_OFFICE, Role.BAR, Role.KITCHEN, Role.LAUNDRY, Role.MANAGER, Role.OWNER);

    private final PaymentRequestRepository payments;
    private final ReceiptRepository receipts;
    private final TerminalService terminals;
    private final PosProvider posProvider;
    private final HotelConfigClient hotelConfig;
    private final EventPublisher events;
    private final ReservationClient reservations;
    private final com.hospio.payment.client.FnbClient fnb;

    @Transactional(readOnly = true)
    public List<PaymentResponse> mine() {
        HospioUserDetails user = CurrentUser.require();
        String hotelId = TenantContext.requireHotel();
        List<PaymentRequest> list = user.isManagerOrOwner() ? payments.findByHotelIdOrderByCreatedAtDesc(hotelId)
                : payments.findByHotelIdAndRequestedByOrderByCreatedAtDesc(hotelId, user.getUserId());
        return list.stream().map(PaymentResponse::of).toList();
    }

    @Transactional(readOnly = true)
    public PaymentResponse get(String id) {
        return PaymentResponse.of(find(id));
    }

    @Transactional(readOnly = true)
    public List<PaymentResponse> forFolio(String folioId) {
        return payments.findByFolioIdOrderByCreatedAtDesc(folioId).stream().map(PaymentResponse::of).toList();
    }

    /** Every payment taken against a booking, folio or not: deposits before check-in live here until the folio opens. */
    @Transactional(readOnly = true)
    public List<PaymentResponse> forBooking(String bookingId) {
        return payments.findByBookingIdOrderByCreatedAtDesc(bookingId).stream().map(PaymentResponse::of).toList();
    }

    @Transactional
    public PaymentResponse request(PaymentRequestDto dto) {
        HospioUserDetails user = CurrentUser.require();
        String hotelId = TenantContext.requireHotel();
        Role role = payingRole(user, dto.role());
        HotelConfigClient.PaymentSettings settings = hotelConfig.paymentSettings(hotelId);
        switch (dto.method()) {
            case CASH -> {
                if (!settings.allowCash()) {
                    throw new BadRequestException("This hotel doesn't take cash");
                }
            }
            case CARD -> {
                if (!settings.allowCard()) {
                    throw new BadRequestException("This hotel doesn't take cards");
                }
            }
            case TRANSFER -> {
                if (!settings.allowTransfer()) {
                    throw new BadRequestException("This hotel doesn't take transfers");
                }
            }
            case ORGANIZATION -> throw new BadRequestException("Organization stays are billed, not paid here");
        }
        if (dto.bookingId() != null && !dto.bookingId().isBlank()) {
            ReservationClient.BookingState booking = reservations.booking(dto.bookingId())
                    .orElseThrow(() -> new BadRequestException("Booking not found"));
            if (booking.hotelId() != null && !booking.hotelId().equals(hotelId)) {
                throw new BadRequestException("Booking not found");
            }
            if ("CANCELLED".equals(booking.status())) {
                throw new BadRequestException("This booking is cancelled. It can't take a payment");
            }
        }
        String desk = blankToNull(dto.orderId()) != null ? checkOrder(dto.orderId(), hotelId, dto.amount()) : null;
        PaymentRequest p = PaymentRequest.builder().folioId(dto.folioId()).bookingId(dto.bookingId()).orderId(blankToNull(dto.orderId())).desk(desk).guestName(dto.guestName())
                .guestPhone(dto.guestPhone()).guestEmail(dto.guestEmail()).requestedBy(user.getUserId()).requestedByName(user.getFullName())
                .role(role).method(dto.method()).amount(Money.of(dto.amount())).description(dto.description())
                .receiptPreference(dto.receiptPreference() == null ? settings.receipts() : dto.receiptPreference()).build();
        if (dto.method() == PaymentMethod.CASH || dto.method() == PaymentMethod.TRANSFER) {
            // Cash is counted at the desk; a transfer is recorded once the desk has seen it land in the hotel's
            // account. Both are approved on the spot; finance matches transfers to the bank line afterwards.
            payments.save(p);
            approve(p, null, dto.method() == PaymentMethod.CASH ? "cash" : "bank transfer");
            return PaymentResponse.of(p);
        }
        return sendToPos(p, role, hotelId);
    }

    /** The requested desk when the caller holds it, else the first of {@link #ROLE_PREFERENCE} they hold. */
    static Role payingRole(HospioUserDetails user, Role requested) {
        if (requested != null) {
            if (!PAYING_ROLES.contains(requested) || !user.has(requested)) {
                throw new BadRequestException("You don't take payments for " + requested.name().toLowerCase().replace('_', ' '));
            }
            return requested;
        }
        return ROLE_PREFERENCE.stream().filter(user::has).findFirst()
                .orElseThrow(() -> new BadRequestException("Your role doesn't take payments"));
    }

    /**
     * A payment for a kitchen or bar order: the order must be this hotel's, still open and not settled, and the amount no more than is due.
     * Returns the desk whose income the order is (BAR or KITCHEN), or null when fnb-service couldn't say.
     */
    private String checkOrder(String orderId, String hotelId, BigDecimal amount) {
        com.hospio.payment.client.FnbClient.OrderDue o = fnb.order(orderId).orElseThrow(() -> new BadRequestException("Order not found"));
        if (o.status() == null) {
            return null; // fnb-service unreachable: take the money, the order is matched when it answers
        }
        if (o.hotelId() != null && !o.hotelId().equals(hotelId)) {
            throw new BadRequestException("Order not found");
        }
        String n = o.number() == null ? "This order" : "Order " + o.number();
        if ("CANCELLED".equals(o.status())) {
            throw new BadRequestException(n + " is cancelled. It can't take a payment");
        }
        if (o.settled() || "CLOSED".equals(o.status())) {
            throw new BadRequestException(n + " is already settled");
        }
        if (o.due() != null && amount != null && amount.compareTo(o.due()) > 0) {
            throw new BadRequestException("Only " + naira(o.due()) + " is due on " + n.replace("Order ", ""));
        }
        return o.desk();
    }

    private static String naira(BigDecimal amount) {
        return "₦" + java.text.NumberFormat.getIntegerInstance(java.util.Locale.UK).format(amount.setScale(0, java.math.RoundingMode.HALF_UP));
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    /**
     * Retry on POS: a card payment the bank declined (or never answered) is sent again as a new request for
     * the same amount, guest, booking and folio, to the POS of the desk that took the first one. The first
     * request stays as it was, so the history shows both.
     */
    @Transactional
    public PaymentResponse retry(String id) {
        HospioUserDetails user = CurrentUser.require();
        String hotelId = TenantContext.requireHotel();
        PaymentRequest old = find(id);
        if (old.getHotelId() != null && !old.getHotelId().equals(hotelId)) {
            throw new ResourceNotFoundException("Payment not found");
        }
        if (!user.isManagerOrOwner() && !user.getUserId().equals(old.getRequestedBy())) {
            throw new BadRequestException("Only a manager or the person who took it can retry this payment");
        }
        if (old.getMethod() != PaymentMethod.CARD) {
            throw new BadRequestException("Only a card payment goes back to the POS");
        }
        if (old.getStatus() != PaymentStatus.DECLINED && old.getStatus() != PaymentStatus.NO_RESPONSE && old.getStatus() != PaymentStatus.FAILED) {
            throw new BadRequestException("Only a declined or unanswered payment can be retried");
        }
        PaymentRequest p = PaymentRequest.builder().folioId(old.getFolioId()).bookingId(old.getBookingId()).orderId(old.getOrderId()).desk(old.getDesk()).guestName(old.getGuestName())
                .guestPhone(old.getGuestPhone()).guestEmail(old.getGuestEmail()).requestedBy(user.getUserId()).requestedByName(user.getFullName())
                .role(old.getRole()).method(PaymentMethod.CARD).amount(old.getAmount()).description(old.getDescription())
                .receiptPreference(old.getReceiptPreference()).build();
        return sendToPos(p, old.getRole(), hotelId);
    }

    private PaymentResponse sendToPos(PaymentRequest p, Role role, String hotelId) {
        PosTerminal terminal = terminals.forRole(hotelId, role)
                .orElseThrow(() -> new BadRequestException("No POS is assigned to " + role.name().toLowerCase().replace('_', ' ') + ". Take cash or ask a manager to assign one in Hotel setup"));
        if (terminal.getStatus() != PosTerminal.Status.ONLINE) {
            throw new BadRequestException("The POS " + terminal.getName() + " is offline. Cash only until it is back");
        }
        p.setTerminalId(terminal.getId());
        p.setProvider(terminal.getProvider());
        payments.save(p);
        try {
            String ref = posProvider.requestPayment(terminal.getTerminalRef(), p.getId(), p.getAmount(), p.getCurrency(),
                    p.getDescription() == null ? "Hospio payment" : p.getDescription());
            p.setProviderRef(ref);
            p.setStatus(PaymentStatus.PROCESSING);
        } catch (Exception e) {
            log.error("POS request failed for payment {}", p.getId(), e);
            terminals.markOffline(terminal, role);
            p.setStatus(PaymentStatus.NO_RESPONSE);
            p.setFailureReason("No response from the POS");
        }
        payments.save(p);
        events.publish(KafkaTopics.PAYMENT_REQUESTED, PaymentRequestedEvent.builder().paymentId(p.getId()).folioId(p.getFolioId())
                .bookingId(p.getBookingId()).amount(p.getAmount()).method(p.getMethod().name()).role(role.name()).terminalId(terminal.getId()).build());
        return PaymentResponse.of(p);
    }

    /** The provider's webhook (Moniepoint/OPay). Public endpoint; the provider adapter validates the body. */
    @Transactional
    public void onWebhook(String provider, Map<String, Object> body, Map<String, String> headers) {
        PosProvider.WebhookResult result = posProvider.parseWebhook(body, headers);
        TenantContext.runWith(Tenant.system("pos-webhook"), () -> {
            PaymentRequest p = null;
            if (result.paymentRequestId() != null) {
                p = payments.findById(result.paymentRequestId()).orElse(null);
            }
            if (p == null && result.providerRef() != null) {
                p = payments.findByProviderRef(result.providerRef()).orElse(null);
            }
            if (p == null) {
                log.warn("Webhook from {} for unknown payment: {}", provider, result);
                return;
            }
            TenantContext.set(Tenant.systemFor(p.getBusinessId(), p.getHotelId(), null, "pos:" + provider));
            if (!p.isPendingOrProcessing()) {
                return;
            }
            switch (result.outcome()) {
                case APPROVED -> approve(p, result.providerRef(), provider);
                case DECLINED -> decline(p, "Declined at the POS");
                case NO_RESPONSE -> {
                    p.setStatus(PaymentStatus.NO_RESPONSE);
                    p.setFailureReason("No response from the POS");
                    payments.save(p);
                }
                case TERMINAL_OFFLINE -> {
                    p.setStatus(PaymentStatus.NO_RESPONSE);
                    p.setFailureReason("POS offline");
                    payments.save(p);
                    if (p.getTerminalId() != null) {
                        terminals.markOffline(terminals.find(p.getTerminalId()), p.getRole());
                    }
                }
                case UNKNOWN -> log.warn("Unknown webhook outcome {} for payment {}", result.rawStatus(), p.getId());
            }
        });
    }

    /** Verify (9.2-9.5): the guest says they paid; ask the provider; settle or report still pending. */
    @Transactional
    public PaymentResponse verify(String id) {
        PaymentRequest p = find(id);
        if (p.getStatus() == PaymentStatus.APPROVED) {
            return PaymentResponse.of(p);
        }
        if (!p.isPendingOrProcessing()) {
            throw new BadRequestException("This payment " + p.getStatus().name().toLowerCase().replace('_', ' '));
        }
        PosProvider.WebhookResult result = posProvider.query(p.getProviderRef()).orElse(null);
        p.setVerifiedAt(Instant.now());
        if (result == null || result.outcome() == PosProvider.Outcome.UNKNOWN || result.outcome() == PosProvider.Outcome.NO_RESPONSE) {
            payments.save(p);
            throw new BadRequestException("Still pending. The POS hasn't confirmed it yet");
        }
        if (result.outcome() == PosProvider.Outcome.APPROVED) {
            approve(p, result.providerRef(), p.getProvider());
        } else {
            decline(p, "Failed at the POS");
        }
        return PaymentResponse.of(p);
    }

    @Transactional
    public PaymentResponse cancel(String id) {
        PaymentRequest p = find(id);
        if (!p.isPendingOrProcessing()) {
            throw new BadRequestException("Only a pending payment can be cancelled");
        }
        p.setStatus(PaymentStatus.FAILED);
        p.setFailureReason("Cancelled by " + CurrentUser.require().getFullName());
        p.setResolvedAt(Instant.now());
        payments.save(p);
        return PaymentResponse.of(p);
    }

    void approve(PaymentRequest p, String providerRef, String provider) {
        p.setStatus(PaymentStatus.APPROVED);
        if (providerRef != null) {
            p.setProviderRef(providerRef);
        }
        p.setResolvedAt(Instant.now());
        payments.save(p);
        if (p.getTerminalId() != null) {
            terminals.markOnline(terminals.find(p.getTerminalId()));
        }
        Receipt receipt = receipts.findByPaymentId(p.getId()).orElseGet(() -> receipts.save(Receipt.builder().paymentId(p.getId())
                .number(nextReceiptNumber(p.getHotelId())).build()));
        events.publish(KafkaTopics.PAYMENT_APPROVED, PaymentApprovedEvent.builder().paymentId(p.getId()).folioId(p.getFolioId())
                .bookingId(p.getBookingId()).guestName(p.getGuestName()).guestPhone(p.getGuestPhone()).guestEmail(p.getGuestEmail())
                .amount(p.getAmount()).method(p.getMethod().name()).role(p.getRole().name()).providerRef(p.getProviderRef())
                .receiptPreference(p.getReceiptPreference()).orderId(p.getOrderId()).desk(p.getDesk()).build());
        log.info("Payment {} approved via {} · receipt {}", p.getId(), provider, receipt.getNumber());
    }

    void decline(PaymentRequest p, String reason) {
        p.setStatus(PaymentStatus.DECLINED);
        p.setFailureReason(reason);
        p.setResolvedAt(Instant.now());
        payments.save(p);
        events.publish(KafkaTopics.PAYMENT_DECLINED, PaymentDeclinedEvent.builder().paymentId(p.getId()).folioId(p.getFolioId())
                .amount(p.getAmount()).method(p.getMethod().name()).reason(reason).build());
    }

    PaymentRequest find(String id) {
        return payments.findById(id).orElseThrow(() -> new ResourceNotFoundException("Payment not found"));
    }

    /** Next number in this hotel's run for the year (RCP-2026-000042): one past the highest issued, so gaps or a reset year never repeat one. */
    private String nextReceiptNumber(String hotelId) {
        String prefix = "RCP-" + LocalDate.now().getYear() + "-";
        long next = receipts.maxNumber(hotelId, prefix)
                .map(n -> n.substring(prefix.length()))
                .filter(n -> n.chars().allMatch(Character::isDigit) && !n.isEmpty())
                .map(Long::parseLong)
                .orElse(0L) + 1;
        return prefix + String.format("%06d", next);
    }
}
