package com.hospio.payment.service;

import com.hospio.common.enums.PaymentMethod;
import com.hospio.common.enums.PaymentStatus;
import com.hospio.common.enums.Role;
import com.hospio.common.exception.BadRequestException;
import com.hospio.common.exception.ResourceNotFoundException;
import com.hospio.common.integrations.PosProvider;
import com.hospio.common.kafka.EventPublisher;
import com.hospio.common.kafka.KafkaTopics;
import com.hospio.common.paging.Paging;
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
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

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
    /**
     * The desks whose cash goes into a till (each has a Till screen). Cash taken for one of them needs that desk's till open, so
     * every naira counted at the close is in a drawer. Laundry hands its cash in (no till screen) and managers and owners have no
     * till of their own: their cash is still recorded, and Today's money lists it as cash taken with no till.
     */
    static final Set<Role> TILL_DESKS = Set.of(Role.FRONT_OFFICE, Role.BAR, Role.KITCHEN);

    private final PaymentRequestRepository payments;
    private final ReceiptRepository receipts;
    private final TerminalService terminals;
    private final PosProvider posProvider;
    private final HotelConfigClient hotelConfig;
    private final EventPublisher events;
    private final ReservationClient reservations;
    private final com.hospio.payment.client.FolioClient folios;
    private final com.hospio.payment.pos.HotelPosRouter hotelPos;
    private final com.hospio.payment.pos.HotelPosService hotelPosConfig;
    private final com.hospio.payment.client.FnbClient fnb;
    private final TransactionTemplate tx;
    private final com.hospio.payment.client.FinanceClient finance;

    /**
     * The unpaged list (scripts, docs/pagination.md): payments taken since {@code from} (a Lagos day; default 90 days ago), newest first.
     * Staff see what they took, managers the hotel's. The screens read the paged form.
     */
    @Transactional(readOnly = true)
    public List<PaymentResponse> mine(LocalDate from) {
        HospioUserDetails user = CurrentUser.require();
        String hotelId = TenantContext.requireHotel();
        Instant since = Paging.windowFrom(from).atStartOfDay(Paging.LAGOS).toInstant();
        List<PaymentRequest> list = user.isManagerOrOwner() ? payments.findByHotelIdAndCreatedAtGreaterThanEqualOrderByCreatedAtDesc(hotelId, since)
                : payments.findByHotelIdAndRequestedByAndCreatedAtGreaterThanEqualOrderByCreatedAtDesc(hotelId, user.getUserId(), since);
        return list.stream().map(PaymentResponse::of).toList();
    }

    @Transactional(readOnly = true)
    public PaymentResponse get(String id) {
        PaymentRequest found = find(id);
        return PaymentResponse.of(found, terminals.nameOf(found.getTerminalId()));
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
        // A payment on a folio counts on its stay too: the booking's "paid" and "due" move with it.
        String bookingId = blankToNull(dto.bookingId()) != null ? dto.bookingId()
                : blankToNull(dto.folioId()) != null ? folios.bookingOf(dto.folioId()).orElse(null) : null;
        if (bookingId != null) {
            ReservationClient.BookingState booking = reservations.booking(bookingId)
                    .orElseThrow(() -> new BadRequestException("Booking not found"));
            if (booking.hotelId() != null && !booking.hotelId().equals(hotelId)) {
                throw new BadRequestException("Booking not found");
            }
            if ("CANCELLED".equals(booking.status())) {
                throw new BadRequestException("This booking is cancelled. It can't take a payment");
            }
        }
        String desk = blankToNull(dto.orderId()) != null ? checkOrder(dto.orderId(), hotelId, dto.amount()) : null;
        PaymentRequest p = PaymentRequest.builder().folioId(dto.folioId()).bookingId(bookingId).orderId(blankToNull(dto.orderId())).desk(desk).guestName(dto.guestName())
                .guestPhone(dto.guestPhone()).guestEmail(dto.guestEmail()).requestedBy(user.getUserId()).requestedByName(user.getFullName())
                .role(role).method(dto.method()).amount(Money.of(dto.amount())).description(dto.description())
                .receiptPreference(dto.receiptPreference() == null ? settings.receipts() : dto.receiptPreference()).build();
        if (dto.method() == PaymentMethod.CASH) {
            requireOpenTill(hotelId, role);
            // Cash is counted at the desk and approved on the spot.
            payments.save(p);
            approve(p, null, "cash");
            return PaymentResponse.of(p);
        }
        // Card and bank transfer both go to the POS: the terminal asks the guest which (Moniepoint and OPay take either on the
        // same request), and the payment is approved when the provider confirms it.
        return sendToPos(p, role, hotelId, blankToNull(dto.terminalId()));
    }

    /**
     * Cash at a till desk needs that desk's till open: 400 "Open your till first" otherwise. When finance-service can't be reached the
     * cash is let through (it is then recorded as cash with no till, as before), so a finance outage never stops the desk.
     */
    void requireOpenTill(String hotelId, Role role) {
        if (!TILL_DESKS.contains(role)) {
            return;
        }
        if (finance.tillOpen(hotelId, role.name()).orElse(true)) {
            return;
        }
        String desk = deskName(role);
        throw new BadRequestException("Open your till first: cash taken at the " + desk + " goes into the " + desk
                + " till, and it isn't open. Open it on the Till page (count the float), then take the cash. Card and transfer still work");
    }

    /** {@code needed}: cash taken for {@code role} goes into a till; {@code open}: that till is open (true when it isn't needed). */
    public record CashTill(String role, boolean needed, boolean open, String message) {
    }

    @Transactional(readOnly = true)
    public CashTill cashTill(Role requested) {
        HospioUserDetails user = CurrentUser.require();
        String hotelId = TenantContext.requireHotel();
        Role role = payingRole(user, requested);
        if (!TILL_DESKS.contains(role)) {
            return new CashTill(role.name(), false, true, null);
        }
        boolean open = finance.tillOpen(hotelId, role.name()).orElse(true);
        String desk = deskName(role);
        return new CashTill(role.name(), true, open, open ? null : "Open your till first: cash taken at the " + desk + " goes into the " + desk + " till, and it isn't open");
    }

    static String deskName(Role role) {
        return role == Role.FRONT_OFFICE ? "front office" : role.name().toLowerCase(java.util.Locale.ROOT).replace('_', ' ');
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
        if (old.getMethod() != PaymentMethod.CARD && old.getMethod() != PaymentMethod.TRANSFER) {
            throw new BadRequestException("Only a card or transfer payment goes back to the POS");
        }
        if (old.getStatus() != PaymentStatus.DECLINED && old.getStatus() != PaymentStatus.NO_RESPONSE && old.getStatus() != PaymentStatus.FAILED
                && old.getStatus() != PaymentStatus.TIMED_OUT) {
            throw new BadRequestException("Only a declined or unanswered payment can be retried");
        }
        PaymentRequest p = PaymentRequest.builder().folioId(old.getFolioId()).bookingId(old.getBookingId()).orderId(old.getOrderId()).desk(old.getDesk()).guestName(old.getGuestName())
                .guestPhone(old.getGuestPhone()).guestEmail(old.getGuestEmail()).requestedBy(user.getUserId()).requestedByName(user.getFullName())
                .role(old.getRole()).method(old.getMethod()).amount(old.getAmount()).description(old.getDescription())
                .receiptPreference(old.getReceiptPreference()).build();
        // The retry goes to the same POS as the first attempt.
        return sendToPos(p, old.getRole(), hotelId, old.getTerminalId());
    }

    private PaymentResponse sendToPos(PaymentRequest p, Role role, String hotelId, String terminalId) {
        // The POS the desk picked (one of this hotel's), else the one assigned to the desk's role.
        PosTerminal terminal = (terminalId != null
                ? Optional.of(terminals.picked(hotelId, terminalId).orElseThrow(() -> new BadRequestException("That POS isn't one of this hotel's. Pick another")))
                : terminals.forRole(hotelId, role))
                .orElseThrow(() -> new BadRequestException("No POS is assigned to " + role.name().toLowerCase().replace('_', ' ') + ". Pick another POS or take cash; a manager can assign one in Hotel setup"));
        if (terminal.getStatus() != PosTerminal.Status.ONLINE) {
            throw new BadRequestException("The POS " + terminal.getName() + " is offline. Use another POS or take cash");
        }
        if (hotelPosConfig.paused(hotelId)) {
            throw new BadRequestException("POS payments are paused: Hospio360's fee is unpaid for 3 days. Take cash or a transfer; the owner can settle it with Hospio360");
        }
        p.setTerminalId(terminal.getId());
        p.setProvider(terminal.getProvider());
        payments.save(p);
        try {
            // A hotel with its own Moniepoint or OPay keys goes to its provider; any other keeps the server's POS provider.
            String ref = hotelPos.handles(hotelId, terminal.getProvider())
                    ? hotelPos.requestPayment(hotelId, terminal.getProvider(), terminal.getTerminalRef(), p.getId(), p.getAmount())
                    : posProvider.requestPayment(terminal.getTerminalRef(), p.getId(), p.getAmount(), p.getCurrency(),
                            p.getDescription() == null ? "Hospio payment" : p.getDescription());
            p.setProviderRef(ref);
            p.setStatus(PaymentStatus.PROCESSING);
        } catch (com.hospio.common.integrations.PosNotConnectedException e) {
            // A provider not integrated yet (Moniepoint / OPay placeholder): say so; the terminal isn't at fault, so it stays online.
            p.setStatus(PaymentStatus.FAILED);
            p.setFailureReason(e.getMessage());
        } catch (IllegalStateException e) {
            // The hotel's provider refused the request or isn't set up (keys, base URL): the payment fails with the reason; the
            // terminal isn't at fault, so it stays online.
            log.warn("POS request refused for payment {}: {}", p.getId(), e.getMessage());
            p.setStatus(PaymentStatus.FAILED);
            p.setFailureReason(e.getMessage() == null ? "The POS provider refused the payment" : e.getMessage());
        } catch (Exception e) {
            log.error("POS request failed for payment {}", p.getId(), e);
            terminals.markOffline(terminal, role);
            p.setStatus(PaymentStatus.NO_RESPONSE);
            p.setFailureReason("No response from the POS");
        }
        payments.save(p);
        events.publish(KafkaTopics.PAYMENT_REQUESTED, PaymentRequestedEvent.builder().paymentId(p.getId()).folioId(p.getFolioId())
                .bookingId(p.getBookingId()).amount(p.getAmount()).method(p.getMethod().name()).role(role.name()).terminalId(terminal.getId()).build());
        return PaymentResponse.of(p, terminal.getName());
    }

    /** The provider's webhook (Moniepoint/OPay). Public endpoint; the provider adapter validates the body. */
    @Transactional
    public void onWebhook(String provider, Map<String, Object> body, Map<String, String> headers) {
        applyWebhook(provider, posProvider.parseWebhook(body, headers));
    }

    /** A webhook already checked and read (the hotel-keyed OPay and Moniepoint webhooks): settles the payment it names. */
    @Transactional
    public void applyWebhook(String provider, PosProvider.WebhookResult result) {
        TenantContext.runWith(Tenant.system("pos-webhook"), () -> {
            // Locked until commit: a retried webhook, Verify or Cancel on the same payment waits here and then sees it resolved.
            PaymentRequest p = null;
            if (result.paymentRequestId() != null) {
                p = payments.lockById(result.paymentRequestId()).orElse(null);
            }
            if (p == null && result.providerRef() != null) {
                p = payments.lockByProviderRef(result.providerRef()).orElse(null);
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
                case DECLINED -> decline(p, result.reason() == null ? "Declined at the POS" : result.reason());
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

    /**
     * Verify (9.2-9.5): the guest says they paid; ask the provider; settle or report still pending. The provider is asked outside any
     * transaction; the answer is applied with the payment locked and its status read again, so a webhook that settled it meanwhile wins
     * and the payment is approved (and announced) once.
     */
    public PaymentResponse verify(String id) {
        PaymentRequest seen = tx.execute(s -> find(id));
        if (seen.getStatus() == PaymentStatus.APPROVED) {
            return PaymentResponse.of(seen);
        }
        checkPending(seen);
        PosProvider.WebhookResult result = (hotelPos.handles(seen.getHotelId(), seen.getProvider())
                ? hotelPos.query(seen.getHotelId(), seen.getProvider(), seen.getId(), seen.getProviderRef())
                : posProvider.query(seen.getProviderRef())).orElse(null);
        if (result == null || result.outcome() == PosProvider.Outcome.UNKNOWN || result.outcome() == PosProvider.Outcome.NO_RESPONSE) {
            throw new BadRequestException("Still pending. The POS hasn't confirmed it yet");
        }
        return tx.execute(s -> {
            PaymentRequest p = lock(id);
            if (p.getStatus() == PaymentStatus.APPROVED) {
                return PaymentResponse.of(p);
            }
            checkPending(p);
            p.setVerifiedAt(Instant.now());
            if (result.outcome() == PosProvider.Outcome.APPROVED) {
                approve(p, result.providerRef(), p.getProvider());
            } else {
                decline(p, result.reason() == null ? "Failed at the POS" : result.reason());
            }
            return PaymentResponse.of(p);
        });
    }

    /**
     * The POS gave no final answer in time: the payment is marked timed out (locked, its status read again, so an answer that landed
     * meanwhile wins). Answers true when it was timed out. It stays open to the provider: a late approval still settles it.
     */
    @Transactional
    public boolean timeOut(String id, java.time.Duration after) {
        PaymentRequest p = lock(id);
        if (p.getStatus() != PaymentStatus.PENDING && p.getStatus() != PaymentStatus.PROCESSING) {
            return false;
        }
        p.setStatus(PaymentStatus.TIMED_OUT);
        p.setFailureReason("Timed out: no final answer from the POS within " + after.toMinutes() + " minutes");
        p.setResolvedAt(Instant.now());
        payments.save(p);
        return true;
    }

    private static void checkPending(PaymentRequest p) {
        if (!p.isPendingOrProcessing()) {
            throw new BadRequestException("This payment " + p.getStatus().name().toLowerCase().replace('_', ' '));
        }
    }

    /** Cancel locks the payment first: a webhook approving it at the same moment either lands before (and cancel refuses) or after (and sees it failed). */
    @Transactional
    public PaymentResponse cancel(String id) {
        PaymentRequest p = lock(id);
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
        Receipt receipt = receipts.findFirstByPaymentIdOrderByCreatedAtAsc(p.getId()).orElseGet(() -> receipts.save(Receipt.builder().paymentId(p.getId())
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

    private PaymentRequest lock(String id) {
        return payments.lockById(id).orElseThrow(() -> new ResourceNotFoundException("Payment not found"));
    }

    /** Next number in this hotel's run for the year (RCP-2026-000042): one past the highest issued, so gaps or a reset year never repeat one. */
    private String nextReceiptNumber(String hotelId) {
        // Two cash payments at once would both read the same max: the lock (held to commit) makes the second wait for the first's number.
        receipts.lock("rcp:" + hotelId);
        String prefix = "RCP-" + LocalDate.now().getYear() + "-";
        long next = receipts.maxNumber(hotelId, prefix)
                .map(n -> n.substring(prefix.length()))
                .filter(n -> n.chars().allMatch(Character::isDigit) && !n.isEmpty())
                .map(Long::parseLong)
                .orElse(0L) + 1;
        return prefix + String.format("%06d", next);
    }
}
