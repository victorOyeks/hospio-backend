package com.hospio.folio.service;

import com.hospio.common.exception.BadRequestException;
import com.hospio.common.exception.ForbiddenException;
import com.hospio.common.exception.ResourceNotFoundException;
import com.hospio.common.kafka.EventPublisher;
import com.hospio.common.kafka.KafkaTopics;
import com.hospio.common.kafka.events.ApprovalRequestedEvent;
import com.hospio.common.kafka.events.DiscountAppliedEvent;
import com.hospio.common.kafka.events.FolioClosedEvent;
import com.hospio.common.kafka.events.FolioOpenedEvent;
import com.hospio.common.security.CurrentUser;
import com.hospio.common.security.HospioUserDetails;
import com.hospio.common.tenancy.TenantContext;
import com.hospio.common.util.Money;
import com.hospio.folio.config.ApprovalLimits;
import com.hospio.folio.dto.ChargeRequest;
import com.hospio.folio.dto.DiscountRequest;
import com.hospio.folio.dto.FolioResponse;
import com.hospio.folio.dto.MoveLinesRequest;
import com.hospio.folio.dto.VoidRequest;
import com.hospio.folio.entity.Folio;
import com.hospio.folio.entity.FolioLine;
import com.hospio.folio.entity.PendingDiscount;
import com.hospio.folio.repository.FolioLineRepository;
import com.hospio.folio.repository.FolioRepository;
import com.hospio.folio.repository.PendingDiscountRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The guest's bill. Charges arrive from every department as ChargePosted events, payments as
 * PaymentApproved; the desk posts extras by hand. Discounts and voids are manager-only, and a discount
 * over the manager's limit waits for the owner.
 */
@Service
@RequiredArgsConstructor
public class FolioService {

    private final FolioRepository folios;
    private final FolioLineRepository lines;
    private final PendingDiscountRepository pendingDiscounts;
    private final ApprovalLimits limits;
    private final EventPublisher events;

    @Transactional(readOnly = true)
    public List<FolioResponse> open() {
        // With their lines: the desk's Folios screen shows each folio's charges and payments from this list.
        List<Folio> open = folios.findByHotelIdAndStatusOrderByRoomNumber(TenantContext.requireHotel(), Folio.Status.OPEN);
        if (open.isEmpty()) {
            return List.of();
        }
        java.util.Map<String, List<FolioLine>> byFolio = lines.findByFolioIdInOrderByPostedAt(open.stream().map(Folio::getId).toList())
                .stream().collect(java.util.stream.Collectors.groupingBy(FolioLine::getFolioId));
        return open.stream().map(f -> FolioResponse.of(f, byFolio.getOrDefault(f.getId(), List.of()))).toList();
    }

    @Transactional(readOnly = true)
    public FolioResponse get(String id) {
        Folio f = find(id);
        return FolioResponse.of(f, lines.findByFolioIdOrderByPostedAt(f.getId()));
    }

    @Transactional(readOnly = true)
    public FolioResponse byBooking(String bookingId) {
        Folio f = folios.findFirstByBookingIdAndStatus(bookingId, Folio.Status.OPEN)
                .or(() -> folios.findByBookingIdOrderByOpenedAt(bookingId).stream().reduce((a, b) -> b))
                .orElseThrow(() -> new ResourceNotFoundException("No folio for that booking"));
        return FolioResponse.of(f, lines.findByFolioIdOrderByPostedAt(f.getId()));
    }

    /** Open folios that still owe money: the night audit's "balances" check lists the departures among them. */
    @Transactional(readOnly = true)
    public List<OpenBalance> openBalances(String hotelId) {
        return folios.openWithBalance(hotelId).stream()
                .map(f -> new OpenBalance(f.getId(), f.getBookingId(), f.getGuestName(), f.getRoomNumber(), f.getBalance())).toList();
    }

    public record OpenBalance(String folioId, String bookingId, String guestName, String roomNumber, BigDecimal balance) {
    }

    @Transactional(readOnly = true)
    public Optional<Folio> activeForRoom(String roomId) {
        return folios.findFirstByRoomIdAndStatus(roomId, Folio.Status.OPEN);
    }

    @Transactional
    public Folio open(String bookingId, String guestId, String guestName, String guestPhone, String guestEmail, String roomId,
                      String roomNumber, String organizationId) {
        Folio existing = folios.findFirstByBookingIdAndStatus(bookingId, Folio.Status.OPEN).orElse(null);
        if (existing != null) {
            return existing;
        }
        Folio f = folios.save(Folio.builder().bookingId(bookingId).guestId(guestId).guestName(guestName).guestPhone(guestPhone)
                .guestEmail(guestEmail).roomId(roomId).roomNumber(roomNumber).organizationId(organizationId).build());
        events.publish(KafkaTopics.FOLIO_OPENED, FolioOpenedEvent.builder().folioId(f.getId()).bookingId(bookingId).guestName(guestName).build());
        return f;
    }

    /** Idempotent on (sourceModule, sourceRef): a replayed event never posts twice. */
    @Transactional
    public FolioLine postCharge(Folio folio, String sourceModule, String sourceRef, String description, BigDecimal amount,
                                BigDecimal tax, String category, String postedBy, String postedByName) {
        if (sourceRef != null) {
            Optional<FolioLine> dup = lines.findBySourceModuleAndSourceRef(sourceModule, sourceRef);
            if (dup.isPresent()) {
                return dup.get();
            }
        }
        if (!folio.isOpen()) {
            throw new BadRequestException("This folio is closed");
        }
        FolioLine line = lines.save(FolioLine.builder().folioId(folio.getId()).kind(FolioLine.Kind.CHARGE).sourceModule(sourceModule)
                .sourceRef(sourceRef).category(category).description(description).amount(Money.of(amount)).taxAmount(Money.of(tax))
                .postedBy(postedBy).postedByName(postedByName).build());
        recalculate(folio);
        return line;
    }

    @Transactional
    public FolioResponse postManualCharge(String folioId, ChargeRequest request) {
        HospioUserDetails user = CurrentUser.require();
        Folio f = find(folioId);
        postCharge(f, "FRONT_OFFICE", null, request.description(), request.amount(), request.taxAmount(), request.category(),
                user.getUserId(), user.getFullName());
        return get(folioId);
    }

    @Transactional
    public void recordPayment(Folio folio, String paymentId, BigDecimal amount, String method, String postedByName) {
        if (lines.findBySourceModuleAndSourceRef("PAYMENT", paymentId).isPresent()) {
            return;
        }
        lines.save(FolioLine.builder().folioId(folio.getId()).kind(FolioLine.Kind.PAYMENT).sourceModule("PAYMENT").sourceRef(paymentId)
                .description("Payment by " + method.toLowerCase()).amount(Money.of(amount)).postedByName(postedByName).build());
        recalculate(folio);
    }

    @Transactional
    public void recordRefund(Folio folio, String refundId, BigDecimal amount, String postedByName) {
        if (lines.findBySourceModuleAndSourceRef("REFUND", refundId).isPresent()) {
            return;
        }
        lines.save(FolioLine.builder().folioId(folio.getId()).kind(FolioLine.Kind.REFUND).sourceModule("REFUND").sourceRef(refundId)
                .description("Refund").amount(Money.of(amount)).postedByName(postedByName).build());
        recalculate(folio);
    }

    /** What a discount did: applied (sentTo null), or sent to the manager or owner to approve. */
    public record DiscountResult(FolioResponse folio, String sentTo) {
    }

    /**
     * A manager applies a discount up to their limit; over it the discount goes to the owner. Front office
     * can't change a bill: their discount is sent to the manager (or the owner, over the manager's limit)
     * and applied when it is approved.
     */
    @Transactional
    public DiscountResult discount(String folioId, DiscountRequest request) {
        HospioUserDetails user = CurrentUser.require();
        boolean manager = user.isManagerOrOwner() || user.isPlatform();
        if (!manager && !user.has(com.hospio.common.enums.Role.FRONT_OFFICE, com.hospio.common.enums.AccessLevel.EDIT)) {
            throw new ForbiddenException("Discounts are for managers");
        }
        Folio f = find(folioId);
        BigDecimal amount = Money.of(request.amount());
        if (amount.signum() <= 0 || amount.compareTo(f.getBalance()) > 0) {
            throw new BadRequestException("The discount has to be between 0 and the balance of " + f.getBalance());
        }
        BigDecimal discountLimit = limits.discountLimit(f.getHotelId(), Money.add(f.getTotalCharges(), f.getTotalTax()));
        boolean overLimit = amount.compareTo(discountLimit) > 0;
        if (!manager) {
            String level = overLimit ? "OWNER" : "MANAGER";
            sendForApproval(f, amount, request.reason(), user, level);
            return new DiscountResult(get(folioId), level);
        }
        if (!user.isPlatform() && !user.has(com.hospio.common.enums.Role.OWNER) && overLimit) {
            sendForApproval(f, amount, request.reason(), user, "OWNER");
            return new DiscountResult(get(folioId), "OWNER");
        }
        applyDiscount(f, amount, request.reason(), user.getUserId(), user.getFullName());
        return new DiscountResult(get(folioId), null);
    }

    private void sendForApproval(Folio f, BigDecimal amount, String reason, HospioUserDetails user, String level) {
        String approvalId = UUID.randomUUID().toString();
        pendingDiscounts.save(PendingDiscount.builder().folioId(f.getId()).approvalId(approvalId).amount(amount).reason(reason).build());
        events.publish(KafkaTopics.APPROVAL_REQUESTED, ApprovalRequestedEvent.builder()
                .approvalId(approvalId).sourceModule("FOLIO").sourceRef(f.getId()).kind("DISCOUNT").amount(amount)
                .requesterId(user.getUserId()).requesterName(user.getFullName()).approverLevel(level)
                .description("Discount of " + amount + " for " + f.getGuestName() + ": " + reason).build());
    }

    @Transactional
    public void applyDiscount(Folio f, BigDecimal amount, String reason, String by, String byName) {
        FolioLine line = lines.save(FolioLine.builder().folioId(f.getId()).kind(FolioLine.Kind.DISCOUNT).sourceModule("FOLIO")
                .description("Discount: " + reason).amount(Money.of(amount)).postedBy(by).postedByName(byName).build());
        recalculate(f);
        events.publish(KafkaTopics.DISCOUNT_APPLIED, DiscountAppliedEvent.builder()
                .folioId(f.getId()).lineId(line.getId()).amount(amount).reason(reason).kind("DISCOUNT").build());
    }

    @Transactional
    public FolioResponse voidLine(String folioId, String lineId, VoidRequest request) {
        HospioUserDetails user = CurrentUser.require();
        if (!user.isManagerOrOwner()) {
            throw new ForbiddenException("Voids are for managers");
        }
        Folio f = find(folioId);
        FolioLine line = lines.findById(lineId).filter(l -> l.getFolioId().equals(f.getId()))
                .orElseThrow(() -> new ResourceNotFoundException("Line not found"));
        if (line.getKind() != FolioLine.Kind.CHARGE) {
            throw new BadRequestException("Only charges can be voided");
        }
        line.setVoided(true);
        line.setVoidReason(request.reason());
        line.setVoidedBy(user.getUserId());
        lines.save(line);
        recalculate(f);
        events.publish(KafkaTopics.DISCOUNT_APPLIED, DiscountAppliedEvent.builder()
                .folioId(f.getId()).lineId(line.getId()).amount(line.getAmount()).reason(request.reason()).kind("VOID").build());
        return get(folioId);
    }

    /** Split: lines go to a new folio for the same stay. Transfer: lines go to another room's open folio. */
    @Transactional
    public FolioResponse moveLines(String folioId, MoveLinesRequest request) {
        HospioUserDetails user = CurrentUser.require();
        if (!user.has(com.hospio.common.enums.Role.FRONT_OFFICE, com.hospio.common.enums.AccessLevel.FULL) && !user.isManagerOrOwner()) {
            throw new ForbiddenException("Moving charges needs Full access");
        }
        Folio from = find(folioId);
        Folio to;
        if (request.targetFolioId() != null) {
            to = find(request.targetFolioId());
        } else if (request.targetRoomId() != null) {
            to = activeForRoom(request.targetRoomId()).orElseThrow(() -> new BadRequestException("Nobody is checked in to that room"));
        } else {
            to = folios.save(Folio.builder().bookingId(from.getBookingId()).guestId(from.getGuestId()).guestName(from.getGuestName())
                    .guestPhone(from.getGuestPhone()).guestEmail(from.getGuestEmail()).roomId(from.getRoomId())
                    .roomNumber(from.getRoomNumber()).organizationId(from.getOrganizationId()).build());
        }
        if (!to.isOpen()) {
            throw new BadRequestException("The target folio is closed");
        }
        for (String lineId : request.lineIds()) {
            FolioLine line = lines.findById(lineId).filter(l -> l.getFolioId().equals(from.getId()))
                    .orElseThrow(() -> new ResourceNotFoundException("Line " + lineId + " is not on this folio"));
            line.setMovedFrom(from.getId());
            line.setFolioId(to.getId());
            lines.save(line);
        }
        recalculate(from);
        recalculate(to);
        return get(to.getId());
    }

    @Transactional
    public void close(String bookingId) {
        folios.findByBookingIdOrderByOpenedAt(bookingId).stream().filter(Folio::isOpen).forEach(f -> {
            recalculate(f);
            f.setStatus(f.getBalance().signum() > 0 && f.getOrganizationId() == null ? Folio.Status.CLOSED_WITH_BALANCE : Folio.Status.CLOSED);
            f.setClosedAt(Instant.now());
            folios.save(f);
            events.publish(KafkaTopics.FOLIO_CLOSED, FolioClosedEvent.builder().folioId(f.getId()).bookingId(bookingId).guestName(f.getGuestName())
                    .total(f.getTotalCharges().add(f.getTotalTax())).paid(f.getTotalPayments()).balance(f.getBalance()).build());
        });
    }

    /** The stay summary sent at check-out: every line and the totals, as plain text. */
    @Transactional(readOnly = true)
    public String staySummary(String folioId) {
        Folio f = find(folioId);
        StringBuilder sb = new StringBuilder("Stay summary for ").append(f.getGuestName());
        if (f.getRoomNumber() != null) {
            sb.append(" · Room ").append(f.getRoomNumber());
        }
        sb.append('\n');
        for (FolioLine l : lines.findByFolioIdOrderByPostedAt(folioId)) {
            if (l.isVoided()) {
                continue;
            }
            sb.append(l.getPostedAt().toString(), 0, 10).append("  ").append(l.getDescription()).append("  ")
                    .append(l.getKind() == FolioLine.Kind.CHARGE ? "" : "-").append(l.getAmount().add(l.getTaxAmount())).append('\n');
        }
        sb.append("Charges ").append(f.getTotalCharges().add(f.getTotalTax())).append(" · Discounts ").append(f.getTotalDiscounts())
                .append(" · Paid ").append(f.getTotalPayments()).append(" · Balance ").append(f.getBalance());
        return sb.toString();
    }

    void recalculate(Folio f) {
        BigDecimal charges = BigDecimal.ZERO, tax = BigDecimal.ZERO, discounts = BigDecimal.ZERO, payments = BigDecimal.ZERO;
        for (FolioLine l : lines.findByFolioIdOrderByPostedAt(f.getId())) {
            if (l.isVoided()) {
                continue;
            }
            switch (l.getKind()) {
                case CHARGE -> {
                    charges = charges.add(l.getAmount());
                    tax = tax.add(l.getTaxAmount());
                }
                case DISCOUNT -> discounts = discounts.add(l.getAmount());
                case PAYMENT -> payments = payments.add(l.getAmount());
                case REFUND -> payments = payments.subtract(l.getAmount());
            }
        }
        f.setTotalCharges(Money.of(charges));
        f.setTotalTax(Money.of(tax));
        f.setTotalDiscounts(Money.of(discounts));
        f.setTotalPayments(Money.of(payments));
        f.setBalance(Money.of(charges.add(tax).subtract(discounts).subtract(payments)));
        folios.save(f);
    }

    Folio find(String id) {
        return folios.findById(id).orElseThrow(() -> new ResourceNotFoundException("Folio not found"));
    }
}
