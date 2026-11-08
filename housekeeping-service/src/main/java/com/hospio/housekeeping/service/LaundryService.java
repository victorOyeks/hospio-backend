package com.hospio.housekeeping.service;

import com.hospio.common.exception.BadRequestException;
import com.hospio.common.exception.ForbiddenException;
import com.hospio.common.exception.ResourceNotFoundException;
import com.hospio.common.kafka.EventPublisher;
import com.hospio.common.kafka.KafkaTopics;
import com.hospio.common.kafka.events.ApprovalRequestedEvent;
import com.hospio.common.kafka.events.ChargePostedEvent;
import com.hospio.common.security.CurrentUser;
import com.hospio.common.security.HospioUserDetails;
import com.hospio.common.tenancy.TenantContext;
import com.hospio.common.util.Money;
import com.hospio.housekeeping.client.ReservationClient;
import com.hospio.housekeeping.dto.TaskRequests;
import com.hospio.housekeeping.entity.DamagedLinen;
import com.hospio.housekeeping.entity.LaundryOrder;
import com.hospio.housekeeping.entity.LinenMovement;
import com.hospio.housekeeping.repository.DamagedLinenRepository;
import com.hospio.housekeeping.repository.LaundryOrderRepository;
import com.hospio.housekeeping.repository.LinenMovementRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Laundry 1.x-3.x: guest laundry orders, hotel linen in and out, damaged linen and write-offs. */
@Service
@RequiredArgsConstructor
public class LaundryService {

    private final LaundryOrderRepository orders;
    private final LinenMovementRepository movements;
    private final DamagedLinenRepository damaged;
    private final ReservationClient reservations;
    private final EventPublisher events;

    @Transactional(readOnly = true)
    public List<LaundryOrder> jobs() {
        return orders.findByHotelIdAndStatusInOrderByCreatedAt(TenantContext.requireHotel(),
                EnumSet.of(LaundryOrder.Status.RECEIVED, LaundryOrder.Status.WASHING, LaundryOrder.Status.READY));
    }

    @Transactional(readOnly = true)
    public LaundryOrder get(String id) {
        return orders.findById(id).orElseThrow(() -> new ResourceNotFoundException("Laundry order not found"));
    }

    @Transactional
    public LaundryOrder create(TaskRequests.LaundryOrderRequest request) {
        HospioUserDetails user = CurrentUser.require();
        LaundryOrder o = LaundryOrder.builder().items(request.items()).total(Money.of(request.total())).guestName(request.guestName())
                .receivedBy(user.getUserId()).build();
        if (request.roomId() != null) {
            ReservationClient.ActiveStay stay = reservations.requireActiveStay(request.roomId());
            o.setRoomId(request.roomId());
            o.setRoomNumber(stay.roomNumber());
            o.setBookingId(stay.bookingId());
            o.setGuestName(stay.guestName());
        }
        orders.save(o);
        if (Boolean.TRUE.equals(request.chargeToRoom())) {
            chargeToRoom(o);
        }
        return o;
    }

    @Transactional
    public LaundryOrder setStatus(String id, LaundryOrder.Status status) {
        LaundryOrder o = get(id);
        o.setStatus(status);
        if (status == LaundryOrder.Status.DELIVERED) {
            if (!o.isPaid() && !o.isChargedToRoom() && o.getTotal().signum() > 0) {
                throw new BadRequestException("Take payment or charge the room before marking delivered");
            }
            o.setDeliveredAt(Instant.now());
        }
        return orders.save(o);
    }

    @Transactional
    public LaundryOrder chargeToRoom(String id) {
        return chargeToRoom(get(id));
    }

    private LaundryOrder chargeToRoom(LaundryOrder o) {
        if (o.getBookingId() == null) {
            throw new BadRequestException("This order isn't linked to a stay");
        }
        if (o.isPaid() || o.isChargedToRoom()) {
            throw new BadRequestException("This order is already settled");
        }
        o.setChargedToRoom(true);
        orders.save(o);
        events.publish(KafkaTopics.CHARGE_POSTED, ChargePostedEvent.builder().bookingId(o.getBookingId()).roomId(o.getRoomId())
                .roomNumber(o.getRoomNumber()).sourceModule("LAUNDRY").sourceRef("LAUNDRY-" + o.getId()).description("Laundry")
                .amount(o.getTotal()).taxAmount(BigDecimal.ZERO).category("LAUNDRY").build());
        return o;
    }

    @Transactional
    public LaundryOrder markPaid(String id, String paymentId) {
        LaundryOrder o = get(id);
        o.setPaid(true);
        o.setPaymentId(paymentId);
        return orders.save(o);
    }

    @Transactional
    public LaundryOrder pocketNote(String id, TaskRequests.PocketNoteRequest request) {
        LaundryOrder o = get(id);
        o.setPocketNote(request.note());
        return orders.save(o);
    }

    @Transactional(readOnly = true)
    public List<LinenMovement> linenMovements() {
        return movements.findByHotelIdOrderByCreatedAtDesc(TenantContext.requireHotel());
    }

    @Transactional
    public List<LinenMovement> recordLinen(TaskRequests.LinenBatchRequest request) {
        String by = CurrentUser.require().getUserId();
        return request.movements().stream().map(m -> movements.save(LinenMovement.builder().direction(m.direction()).item(m.item())
                .quantity(m.quantity()).note(m.note()).recordedBy(by).build())).toList();
    }

    @Transactional(readOnly = true)
    public List<DamagedLinen> damaged() {
        return damaged.findByHotelIdOrderByCreatedAtDesc(TenantContext.requireHotel());
    }

    @Transactional
    public DamagedLinen reportDamaged(TaskRequests.DamagedLinenRequest request) {
        return damaged.save(DamagedLinen.builder().item(request.item()).quantity(request.quantity()).reason(request.reason())
                .reportedBy(CurrentUser.require().getUserId()).build());
    }

    /** Ask a supervisor (manager) to write the linen off. */
    @Transactional
    public DamagedLinen askToWriteOff(String id) {
        HospioUserDetails user = CurrentUser.require();
        DamagedLinen d = damaged.findById(id).orElseThrow(() -> new ResourceNotFoundException("Not found"));
        if (d.getApprovalId() != null) {
            throw new BadRequestException("Already sent for approval");
        }
        d.setApprovalId(UUID.randomUUID().toString());
        d.setStatus("PENDING_WRITEOFF");
        damaged.save(d);
        events.publish(KafkaTopics.APPROVAL_REQUESTED, ApprovalRequestedEvent.builder().approvalId(d.getApprovalId()).sourceModule("HOUSEKEEPING")
                .sourceRef(d.getId()).kind("LINEN_WRITEOFF").amount(null).requesterId(user.getUserId()).requesterName(user.getFullName())
                .approverLevel("MANAGER").description("Write off " + d.getQuantity() + " x " + d.getItem() + ": " + d.getReason()).build());
        return d;
    }

    @Transactional
    public void onWriteOffDecided(String approvalId, String decision) {
        damaged.findByApprovalId(approvalId).ifPresent(d -> {
            d.setStatus("APPROVED".equals(decision) ? "WRITTEN_OFF" : "REJECTED");
            damaged.save(d);
        });
    }

    @Transactional
    public DamagedLinen approveWriteOff(String id) {
        if (!CurrentUser.require().isManagerOrOwner()) {
            throw new ForbiddenException("Only a manager can approve a write-off");
        }
        DamagedLinen d = damaged.findById(id).orElseThrow(() -> new ResourceNotFoundException("Not found"));
        d.setStatus("WRITTEN_OFF");
        return damaged.save(d);
    }
}
