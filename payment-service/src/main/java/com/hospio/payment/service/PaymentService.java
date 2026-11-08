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
import com.hospio.payment.dto.PaymentRequestDto;
import com.hospio.payment.dto.PaymentResponse;
import com.hospio.payment.entity.PaymentRequest;
import com.hospio.payment.entity.PosTerminal;
import com.hospio.payment.entity.Receipt;
import com.hospio.payment.repository.PaymentRequestRepository;
import com.hospio.payment.repository.ReceiptRepository;
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
 * The POS model (handoff card 10). Ask card, transfer or cash → card/transfer goes to the role's POS
 * and waits for the provider's answer; cash is approved on the spot. Verify settles a pending one.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentService {

    private static final Set<Role> PAYING_ROLES = Set.of(Role.FRONT_OFFICE, Role.BAR, Role.KITCHEN, Role.LAUNDRY, Role.MANAGER, Role.OWNER);

    private final PaymentRequestRepository payments;
    private final ReceiptRepository receipts;
    private final TerminalService terminals;
    private final PosProvider posProvider;
    private final HotelConfigClient hotelConfig;
    private final EventPublisher events;

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
        Role role = user.roleSet().stream().filter(PAYING_ROLES::contains).findFirst()
                .orElseThrow(() -> new BadRequestException("Your role doesn't take payments"));
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
        PaymentRequest p = PaymentRequest.builder().folioId(dto.folioId()).bookingId(dto.bookingId()).guestName(dto.guestName())
                .guestPhone(dto.guestPhone()).guestEmail(dto.guestEmail()).requestedBy(user.getUserId()).requestedByName(user.getFullName())
                .role(role).method(dto.method()).amount(Money.of(dto.amount())).description(dto.description())
                .receiptPreference(dto.receiptPreference() == null ? settings.receipts() : dto.receiptPreference()).build();
        if (dto.method() == PaymentMethod.CASH) {
            payments.save(p);
            approve(p, null, "cash");
            return PaymentResponse.of(p);
        }
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
                    dto.description() == null ? "Hospio payment" : dto.description());
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
                .number("RCP-" + LocalDate.now().getYear() + "-" + String.format("%06d", receipts.countByHotelId(p.getHotelId()) + 1)).build()));
        events.publish(KafkaTopics.PAYMENT_APPROVED, PaymentApprovedEvent.builder().paymentId(p.getId()).folioId(p.getFolioId())
                .bookingId(p.getBookingId()).guestName(p.getGuestName()).guestPhone(p.getGuestPhone()).guestEmail(p.getGuestEmail())
                .amount(p.getAmount()).method(p.getMethod().name()).role(p.getRole().name()).providerRef(p.getProviderRef())
                .receiptPreference(p.getReceiptPreference()).build());
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
}
