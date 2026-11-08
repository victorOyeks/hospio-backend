package com.hospio.folio.kafka;

import com.hospio.common.kafka.KafkaTopics;
import com.hospio.common.kafka.events.ApprovalDecidedEvent;
import com.hospio.common.kafka.events.ChargePostedEvent;
import com.hospio.common.kafka.events.GuestCheckedInEvent;
import com.hospio.common.kafka.events.GuestCheckedOutEvent;
import com.hospio.common.kafka.events.PaymentApprovedEvent;
import com.hospio.common.kafka.events.RefundApprovedEvent;
import com.hospio.common.tenancy.TenantBinder;
import com.hospio.common.tenancy.TenantContext;
import com.hospio.folio.entity.Folio;
import com.hospio.folio.entity.PendingDiscount;
import com.hospio.folio.repository.FolioRepository;
import com.hospio.folio.repository.PendingDiscountRepository;
import com.hospio.folio.service.FolioService;
import java.math.BigDecimal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * How the folio learns what happened elsewhere: a check-in opens it and posts the room, every
 * department's ChargePosted lands on it, an approved payment settles it, check-out closes it.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FolioEventListener {

    private final FolioService folioService;
    private final FolioRepository folios;
    private final PendingDiscountRepository pendingDiscounts;
    private final TenantBinder tenant;

    @Transactional
    @KafkaListener(topics = KafkaTopics.GUEST_CHECKED_IN)
    public void onCheckedIn(GuestCheckedInEvent e) {
        tenant.bind(e);
        try {
            Folio folio = folioService.open(e.getBookingId(), e.getGuestId(), e.getGuestName(), e.getGuestPhone(), e.getGuestEmail(),
                    e.getRoomId(), e.getRoomNumber(), e.getOrganizationId());
            BigDecimal rate = e.getNightlyRate() == null ? BigDecimal.ZERO : e.getNightlyRate();
            int nights = e.getNights() == null ? 1 : e.getNights();
            if (rate.signum() > 0) {
                folioService.postCharge(folio, "RESERVATION", "ROOM-" + e.getBookingId(),
                        "Room " + e.getRoomNumber() + " · " + nights + (nights == 1 ? " night" : " nights"),
                        rate.multiply(BigDecimal.valueOf(nights)), BigDecimal.ZERO, "ACCOMMODATION", e.getActorId(), e.getActorName());
            }
        } finally {
            TenantContext.clear();
        }
    }

    @Transactional
    @KafkaListener(topics = KafkaTopics.CHARGE_POSTED)
    public void onChargePosted(ChargePostedEvent e) {
        tenant.bind(e);
        try {
            Folio folio = null;
            if (e.getFolioId() != null) {
                folio = folios.findById(e.getFolioId()).orElse(null);
            }
            if (folio == null && e.getBookingId() != null) {
                folio = folios.findFirstByBookingIdAndStatus(e.getBookingId(), Folio.Status.OPEN).orElse(null);
            }
            if (folio == null && e.getRoomId() != null) {
                folio = folioService.activeForRoom(e.getRoomId()).orElse(null);
            }
            if (folio == null) {
                log.warn("ChargePosted {} from {} has no open folio (booking {}, room {})", e.getSourceRef(), e.getSourceModule(), e.getBookingId(), e.getRoomId());
                return;
            }
            folioService.postCharge(folio, e.getSourceModule(), e.getSourceRef(), e.getDescription(), e.getAmount(), e.getTaxAmount(),
                    e.getCategory(), e.getActorId(), e.getActorName());
        } finally {
            TenantContext.clear();
        }
    }

    @Transactional
    @KafkaListener(topics = KafkaTopics.PAYMENT_APPROVED)
    public void onPaymentApproved(PaymentApprovedEvent e) {
        tenant.bind(e);
        try {
            if (e.getFolioId() == null) {
                return;
            }
            folios.findById(e.getFolioId()).ifPresent(f -> folioService.recordPayment(f, e.getPaymentId(), e.getAmount(), e.getMethod(), e.getActorName()));
        } finally {
            TenantContext.clear();
        }
    }

    @Transactional
    @KafkaListener(topics = KafkaTopics.REFUND_APPROVED)
    public void onRefundApproved(RefundApprovedEvent e) {
        tenant.bind(e);
        try {
            if (e.getFolioId() == null) {
                return;
            }
            folios.findById(e.getFolioId()).ifPresent(f -> folioService.recordRefund(f, e.getRefundId(), e.getAmount(), e.getActorName()));
        } finally {
            TenantContext.clear();
        }
    }

    @Transactional
    @KafkaListener(topics = KafkaTopics.GUEST_CHECKED_OUT)
    public void onCheckedOut(GuestCheckedOutEvent e) {
        tenant.bind(e);
        try {
            folioService.close(e.getBookingId());
        } finally {
            TenantContext.clear();
        }
    }

    /** The owner decided on a discount over the manager's limit. */
    @Transactional
    @KafkaListener(topics = KafkaTopics.APPROVAL_DECIDED)
    public void onApprovalDecided(ApprovalDecidedEvent e) {
        if (!"FOLIO".equals(e.getSourceModule())) {
            return;
        }
        tenant.bind(e);
        try {
            PendingDiscount pending = pendingDiscounts.findByApprovalId(e.getApprovalId()).orElse(null);
            if (pending == null || !"PENDING".equals(pending.getStatus())) {
                return;
            }
            pending.setStatus(e.getDecision());
            pendingDiscounts.save(pending);
            if ("APPROVED".equals(e.getDecision())) {
                folios.findById(pending.getFolioId()).ifPresent(f -> folioService.applyDiscount(f, pending.getAmount(), pending.getReason(),
                        e.getDecidedBy(), e.getDecidedByName()));
            }
        } finally {
            TenantContext.clear();
        }
    }
}
