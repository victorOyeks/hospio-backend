package com.hospio.maintenance.service;

import com.hospio.common.exception.BadRequestException;
import com.hospio.common.exception.ResourceNotFoundException;
import com.hospio.common.kafka.EventPublisher;
import com.hospio.common.kafka.KafkaTopics;
import com.hospio.common.kafka.events.ApprovalRequestedEvent;
import com.hospio.common.kafka.events.PartsRequestedEvent;
import com.hospio.common.kafka.events.RepairDoneEvent;
import com.hospio.common.kafka.events.RepairOpenedEvent;
import com.hospio.common.security.CurrentUser;
import com.hospio.common.security.HospioUserDetails;
import com.hospio.common.tenancy.Tenant;
import com.hospio.common.tenancy.TenantContext;
import com.hospio.common.util.Money;
import com.hospio.maintenance.client.PropertyClient;
import com.hospio.maintenance.dto.MaintenanceRequests;
import com.hospio.maintenance.entity.PartRequest;
import com.hospio.maintenance.entity.Repair;
import com.hospio.maintenance.entity.RepairUpdate;
import com.hospio.maintenance.entity.ScheduledJob;
import com.hospio.maintenance.entity.ScheduledJobRun;
import com.hospio.maintenance.entity.SparePart;
import com.hospio.maintenance.repository.PartRequestRepository;
import com.hospio.maintenance.repository.RepairRepository;
import com.hospio.maintenance.repository.RepairUpdateRepository;
import com.hospio.maintenance.repository.ScheduledJobRepository;
import com.hospio.maintenance.repository.ScheduledJobRunRepository;
import com.hospio.maintenance.repository.SparePartRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Maintenance 1.x-4.x: repairs, rooms out of order, scheduled jobs and spare parts. */
@Slf4j
@Service
@RequiredArgsConstructor
public class MaintenanceService {

    private static final EnumSet<Repair.Status> OPEN = EnumSet.of(Repair.Status.OPEN, Repair.Status.ASSIGNED, Repair.Status.IN_PROGRESS, Repair.Status.WAITING_PARTS);

    private final RepairRepository repairs;
    private final RepairUpdateRepository updates;
    private final ScheduledJobRepository jobs;
    private final ScheduledJobRunRepository runs;
    private final SparePartRepository parts;
    private final PartRequestRepository partRequests;
    private final PropertyClient property;
    private final EventPublisher events;

    @Value("${hospio.approvals.manager-spend-limit:500000}")
    private BigDecimal managerLimit;

    @Transactional(readOnly = true)
    public List<Repair> myRepairs(boolean all) {
        HospioUserDetails user = CurrentUser.require();
        String hotelId = TenantContext.requireHotel();
        return all || user.isManagerOrOwner() ? repairs.findByHotelIdAndStatusInOrderByPriorityDescCreatedAtAsc(hotelId, OPEN)
                : repairs.findByHotelIdAndAssignedToAndStatusInOrderByPriorityDescCreatedAtAsc(hotelId, user.getUserId(), OPEN);
    }

    @Transactional(readOnly = true)
    public Repair get(String id) {
        return repairs.findById(id).orElseThrow(() -> new ResourceNotFoundException("Repair not found"));
    }

    @Transactional(readOnly = true)
    public List<RepairUpdate> updates(String repairId) {
        return updates.findByRepairIdOrderByCreatedAt(repairId);
    }

    @Transactional
    public Repair open(MaintenanceRequests.RepairRequest request) {
        String by = TenantContext.current().map(Tenant::actorId).orElse(null);
        Repair r = repairs.save(Repair.builder().roomId(blankToNull(request.roomId())).roomNumber(blankToNull(request.roomNumber()))
                .area(request.area()).title(request.title()).description(request.description())
                .priority(request.priority() == null ? Repair.Priority.NORMAL : request.priority())
                .source(request.source() == null ? "MAINTENANCE" : request.source()).photoKey(blankToNull(request.photoKey())).reportedBy(by).build());
        events.publish(KafkaTopics.REPAIR_OPENED, RepairOpenedEvent.builder().repairId(r.getId()).roomId(r.getRoomId())
                .roomNumber(r.getRoomNumber()).area(r.getArea()).title(r.getTitle()).priority(r.getPriority().name()).build());
        return r;
    }

    @Transactional
    public Repair assign(String id, MaintenanceRequests.AssignRequest request) {
        Repair r = get(id);
        r.setAssignedTo(request.assignedTo());
        r.setAssignedToName(request.assignedToName());
        if (r.getStatus() == Repair.Status.OPEN) {
            r.setStatus(Repair.Status.ASSIGNED);
        }
        return repairs.save(r);
    }

    @Transactional
    public Repair addUpdate(String id, MaintenanceRequests.UpdateRequest request) {
        HospioUserDetails user = CurrentUser.require();
        Repair r = get(id);
        updates.save(RepairUpdate.builder().repairId(r.getId()).note(request.note()).photoKey(request.photoKey())
                .byUser(user.getUserId()).byName(user.getFullName()).build());
        if (request.status() != null && request.status() != Repair.Status.DONE) {
            r.setStatus(request.status());
        } else if (r.getStatus() == Repair.Status.ASSIGNED || r.getStatus() == Repair.Status.OPEN) {
            r.setStatus(Repair.Status.IN_PROGRESS);
        }
        return repairs.save(r);
    }

    @Transactional
    public Repair done(String id, MaintenanceRequests.DoneRequest request) {
        HospioUserDetails user = CurrentUser.require();
        Repair r = get(id);
        r.setStatus(Repair.Status.DONE);
        r.setDoneAt(Instant.now());
        r.setDoneBy(user.getUserId());
        if (request != null && request.note() != null && !request.note().isBlank()) {
            updates.save(RepairUpdate.builder().repairId(r.getId()).note(request.note()).byUser(user.getUserId()).byName(user.getFullName()).build());
        }
        if (r.isRoomOutOfOrder() && r.getRoomId() != null) {
            property.backInService(r.getRoomId());
            r.setRoomOutOfOrder(false);
        }
        repairs.save(r);
        events.publish(KafkaTopics.REPAIR_DONE, RepairDoneEvent.builder().repairId(r.getId()).roomId(r.getRoomId())
                .roomNumber(r.getRoomNumber()).title(r.getTitle()).doneBy(user.getFullName()).build());
        return r;
    }

    @Transactional
    public Repair takeRoomOutOfOrder(String id, MaintenanceRequests.OutOfOrderRequest request) {
        Repair r = get(id);
        if (r.getRoomId() == null) {
            throw new BadRequestException("This repair isn't for a room");
        }
        property.outOfOrder(r.getRoomId(), request.reason(), request.until());
        r.setRoomOutOfOrder(true);
        return repairs.save(r);
    }

    @Transactional
    public Repair putRoomBack(String id) {
        Repair r = get(id);
        if (r.getRoomId() == null) {
            throw new BadRequestException("This repair isn't for a room");
        }
        property.backInService(r.getRoomId());
        r.setRoomOutOfOrder(false);
        return repairs.save(r);
    }

    @Transactional
    public PartRequest askForParts(String repairId, MaintenanceRequests.PartsRequest request) {
        HospioUserDetails user = CurrentUser.require();
        Repair r = get(repairId);
        PartRequest p = PartRequest.builder().repairId(r.getId()).partName(request.partName()).quantity(Math.max(1, request.quantity()))
                .estimatedCost(request.estimatedCost()).requestedBy(user.getUserId()).build();
        BigDecimal cost = Money.of(request.estimatedCost());
        if (cost.signum() > 0) {
            p.setApprovalId(UUID.randomUUID().toString());
            p.setStatus("PENDING_APPROVAL");
        }
        partRequests.save(p);
        r.setStatus(Repair.Status.WAITING_PARTS);
        repairs.save(r);
        if (p.getApprovalId() != null) {
            events.publish(KafkaTopics.APPROVAL_REQUESTED, ApprovalRequestedEvent.builder().approvalId(p.getApprovalId()).sourceModule("MAINTENANCE")
                    .sourceRef(p.getId()).kind("PARTS").amount(cost).requesterId(user.getUserId()).requesterName(user.getFullName())
                    .approverLevel(cost.compareTo(managerLimit) > 0 ? "OWNER" : "MANAGER")
                    .description(p.getQuantity() + " x " + p.getPartName() + " for " + r.getTitle() + " · " + cost).build());
        }
        events.publish(KafkaTopics.PARTS_REQUESTED, PartsRequestedEvent.builder().repairId(r.getId()).partName(p.getPartName())
                .quantity(p.getQuantity()).estimatedCost(cost).build());
        return p;
    }

    @Transactional
    public void onPartsDecided(String approvalId, String decision) {
        partRequests.findByApprovalId(approvalId).ifPresent(p -> {
            p.setStatus("APPROVED".equals(decision) ? "APPROVED" : "DECLINED");
            partRequests.save(p);
        });
    }

    @Transactional(readOnly = true)
    public List<PartRequest> partRequests() {
        return partRequests.findByHotelIdOrderByCreatedAtDesc(TenantContext.requireHotel());
    }

    @Transactional(readOnly = true)
    public List<ScheduledJob> scheduledJobs() {
        return jobs.findByHotelIdAndDeletedFalseOrderByNextDue(TenantContext.requireHotel());
    }

    @Transactional
    public ScheduledJob saveJob(String id, MaintenanceRequests.ScheduledJobRequest request) {
        ScheduledJob j = id == null ? new ScheduledJob() : job(id);
        j.setTitle(request.title());
        j.setDescription(request.description());
        j.setArea(request.area());
        j.setEveryDays(Math.max(1, request.everyDays()));
        if (id == null || request.firstDue() != null) {
            j.setNextDue(request.firstDue() == null ? LocalDate.now().plusDays(j.getEveryDays()) : request.firstDue());
        }
        j.setAssignedTo(request.assignedTo());
        j.setAssignedToName(request.assignedToName());
        return jobs.save(j);
    }

    @Transactional
    public ScheduledJob jobDone(String id, MaintenanceRequests.JobDoneRequest request) {
        ScheduledJob j = job(id);
        runs.save(ScheduledJobRun.builder().jobId(j.getId()).doneOn(LocalDate.now()).note(request == null ? null : request.note())
                .doneBy(CurrentUser.require().getUserId()).build());
        j.setLastDone(LocalDate.now());
        j.setNextDue(LocalDate.now().plusDays(j.getEveryDays()));
        return jobs.save(j);
    }

    @Transactional(readOnly = true)
    public List<ScheduledJobRun> pastRuns(String id) {
        return runs.findByJobIdOrderByDoneOnDesc(id);
    }

    @Transactional
    public ScheduledJob pauseJob(String id, boolean paused) {
        ScheduledJob j = job(id);
        j.setPaused(paused);
        return jobs.save(j);
    }

    @Transactional
    public void deleteJob(String id) {
        ScheduledJob j = job(id);
        j.setDeleted(true);
        jobs.save(j);
    }

    @Transactional(readOnly = true)
    public List<SparePart> spareParts() {
        return parts.findByHotelIdOrderByName(TenantContext.requireHotel());
    }

    @Transactional
    public SparePart savePart(String id, MaintenanceRequests.SparePartRequest request) {
        SparePart p = id == null ? new SparePart() : parts.findById(id).orElseThrow(() -> new ResourceNotFoundException("Part not found"));
        p.setName(request.name());
        if (request.quantity() != null) {
            p.setQuantity(request.quantity());
        }
        if (request.minQuantity() != null) {
            p.setMinQuantity(request.minQuantity());
        }
        p.setUnitCost(request.unitCost());
        return parts.save(p);
    }

    @Transactional
    public SparePart usePart(String id, int quantity) {
        SparePart p = parts.findById(id).orElseThrow(() -> new ResourceNotFoundException("Part not found"));
        if (p.getQuantity() < quantity) {
            throw new BadRequestException("Only " + p.getQuantity() + " left");
        }
        p.setQuantity(p.getQuantity() - quantity);
        return parts.save(p);
    }

    /** The worker: every morning, jobs that fell due become repairs so they show on the board. */
    @Scheduled(cron = "0 0 6 * * *", zone = "Africa/Lagos")
    @Transactional
    public void openDueScheduledJobs() {
        TenantContext.runWith(Tenant.system("scheduled-jobs"), () -> {
            for (ScheduledJob j : jobs.findByDeletedFalseAndPausedFalseAndNextDueLessThanEqual(LocalDate.now())) {
                TenantContext.set(Tenant.systemFor(j.getBusinessId(), j.getHotelId(), null, "system:scheduled-jobs"));
                repairs.save(Repair.builder().title(j.getTitle()).description(j.getDescription()).area(j.getArea()).source("SCHEDULED")
                        .assignedTo(j.getAssignedTo()).assignedToName(j.getAssignedToName())
                        .status(j.getAssignedTo() == null ? Repair.Status.OPEN : Repair.Status.ASSIGNED).build());
                log.info("Scheduled job {} is due at hotel {}", j.getTitle(), j.getHotelId());
            }
        });
    }

    private ScheduledJob job(String id) {
        return jobs.findById(id).filter(j -> !j.isDeleted()).orElseThrow(() -> new ResourceNotFoundException("Scheduled job not found"));
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
