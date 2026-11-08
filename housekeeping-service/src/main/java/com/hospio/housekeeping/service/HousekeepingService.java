package com.hospio.housekeeping.service;

import com.hospio.common.exception.BadRequestException;
import com.hospio.common.exception.ForbiddenException;
import com.hospio.common.exception.ResourceNotFoundException;
import com.hospio.common.kafka.EventPublisher;
import com.hospio.common.kafka.KafkaTopics;
import com.hospio.common.kafka.events.ChargePostedEvent;
import com.hospio.common.kafka.events.LinenRequestedEvent;
import com.hospio.common.kafka.events.ProblemReportedEvent;
import com.hospio.common.kafka.events.RoomCleanedEvent;
import com.hospio.common.security.CurrentUser;
import com.hospio.common.security.HospioUserDetails;
import com.hospio.common.tenancy.TenantContext;
import com.hospio.common.util.Money;
import com.hospio.housekeeping.client.MaintenanceClient;
import com.hospio.housekeeping.client.ReservationClient;
import com.hospio.housekeeping.dto.TaskRequests;
import com.hospio.housekeeping.entity.CleaningTask;
import com.hospio.housekeeping.entity.LinenRequest;
import com.hospio.housekeeping.entity.LostFoundItem;
import com.hospio.housekeeping.entity.MinibarRestock;
import com.hospio.housekeeping.entity.ProblemReport;
import com.hospio.housekeeping.entity.RoomStatus;
import com.hospio.housekeeping.repository.CleaningTaskRepository;
import com.hospio.housekeeping.repository.LinenRequestRepository;
import com.hospio.housekeeping.repository.LostFoundItemRepository;
import com.hospio.housekeeping.repository.MinibarRestockRepository;
import com.hospio.housekeeping.repository.ProblemReportRepository;
import com.hospio.housekeeping.repository.RoomStatusRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Housekeeping 1.x-3.x: my rooms, checklists, problems, linen, minibar, lost & found, supervisor assign and inspect. */
@Service
@RequiredArgsConstructor
public class HousekeepingService {

    private final CleaningTaskRepository tasks;
    private final RoomStatusRepository roomStatuses;
    private final ProblemReportRepository problems;
    private final LinenRequestRepository linenRequests;
    private final MinibarRestockRepository minibars;
    private final LostFoundItemRepository lostFound;
    private final MaintenanceClient maintenance;
    private final ReservationClient reservations;
    private final EventPublisher events;

    @Transactional(readOnly = true)
    public List<CleaningTask> myRooms(LocalDate date) {
        HospioUserDetails user = CurrentUser.require();
        LocalDate d = date == null ? LocalDate.now() : date;
        return user.isManagerOrOwner() || user.has(com.hospio.common.enums.Role.HOUSEKEEPING, com.hospio.common.enums.AccessLevel.FULL)
                ? tasks.findByHotelIdAndDueDateOrderByRoomNumber(TenantContext.requireHotel(), d)
                : tasks.findByHotelIdAndAssignedToAndDueDateOrderByRoomNumber(TenantContext.requireHotel(), user.getUserId(), d);
    }

    @Transactional(readOnly = true)
    public List<CleaningTask> toInspect() {
        return tasks.findByHotelIdAndStatusOrderByDoneAt(TenantContext.requireHotel(), CleaningTask.Status.DONE);
    }

    @Transactional(readOnly = true)
    public List<RoomStatus> roomBoard(String hotelId) {
        return roomStatuses.findByHotelIdOrderByRoomNumber(hotelId);
    }

    @Transactional
    public CleaningTask assign(TaskRequests.AssignRequest request) {
        requireSupervisor();
        return tasks.save(CleaningTask.builder().roomId(request.roomId()).roomNumber(request.roomNumber()).kind(request.kind())
                .dueDate(request.dueDate() == null ? LocalDate.now() : request.dueDate()).assignedTo(request.assignedTo())
                .assignedToName(request.assignedToName()).build());
    }

    /** Called from events: a stay-over clean tomorrow after check-in, a departure clean on check-out. */
    @Transactional
    public CleaningTask createTask(String roomId, String roomNumber, CleaningTask.Kind kind, LocalDate dueDate) {
        boolean exists = tasks.findByRoomIdAndStatusIn(roomId, EnumSet.of(CleaningTask.Status.TODO, CleaningTask.Status.IN_PROGRESS)).stream()
                .anyMatch(t -> t.getKind() == kind && t.getDueDate().equals(dueDate));
        if (exists) {
            return null;
        }
        setRoomStatus(roomId, roomNumber, RoomStatus.Cleanliness.DIRTY, null);
        return tasks.save(CleaningTask.builder().roomId(roomId).roomNumber(roomNumber).kind(kind).dueDate(dueDate).build());
    }

    @Transactional
    public CleaningTask reassign(String taskId, TaskRequests.ReassignRequest request) {
        requireSupervisor();
        CleaningTask t = task(taskId);
        t.setAssignedTo(request.assignedTo());
        t.setAssignedToName(request.assignedToName());
        return tasks.save(t);
    }

    @Transactional
    public CleaningTask start(String taskId) {
        CleaningTask t = task(taskId);
        t.setStatus(CleaningTask.Status.IN_PROGRESS);
        t.setStartedAt(Instant.now());
        return tasks.save(t);
    }

    @Transactional
    public CleaningTask saveChecklist(String taskId, TaskRequests.ChecklistRequest request) {
        CleaningTask t = task(taskId);
        t.setChecklist(request.checklist());
        return tasks.save(t);
    }

    @Transactional
    public CleaningTask done(String taskId) {
        HospioUserDetails user = CurrentUser.require();
        CleaningTask t = task(taskId);
        t.setStatus(CleaningTask.Status.DONE);
        t.setDoneAt(Instant.now());
        t.setDoneBy(user.getUserId());
        tasks.save(t);
        setRoomStatus(t.getRoomId(), t.getRoomNumber(), RoomStatus.Cleanliness.CLEAN, user.getUserId());
        events.publish(KafkaTopics.ROOM_CLEANED, RoomCleanedEvent.builder().taskId(t.getId()).roomId(t.getRoomId())
                .roomNumber(t.getRoomNumber()).cleanedBy(user.getFullName()).inspected(false).build());
        return t;
    }

    @Transactional
    public CleaningTask skip(String taskId, TaskRequests.SkipRequest request) {
        CleaningTask t = task(taskId);
        t.setStatus(CleaningTask.Status.SKIPPED);
        t.setSkipReason(request.reason());
        return tasks.save(t);
    }

    @Transactional
    public CleaningTask inspect(String taskId, TaskRequests.InspectRequest request) {
        requireSupervisor();
        HospioUserDetails user = CurrentUser.require();
        CleaningTask t = task(taskId);
        if (t.getStatus() != CleaningTask.Status.DONE) {
            throw new BadRequestException("Only a finished room can be inspected");
        }
        t.setInspectedBy(user.getUserId());
        t.setInspectedAt(Instant.now());
        t.setInspectionNote(request.note());
        if (request.passed()) {
            t.setStatus(CleaningTask.Status.INSPECTED);
            setRoomStatus(t.getRoomId(), t.getRoomNumber(), RoomStatus.Cleanliness.INSPECTED, user.getUserId());
            events.publish(KafkaTopics.ROOM_CLEANED, RoomCleanedEvent.builder().taskId(t.getId()).roomId(t.getRoomId())
                    .roomNumber(t.getRoomNumber()).cleanedBy(t.getAssignedToName()).inspected(true).build());
        } else {
            t.setStatus(CleaningTask.Status.SENT_BACK);
            setRoomStatus(t.getRoomId(), t.getRoomNumber(), RoomStatus.Cleanliness.DIRTY, user.getUserId());
        }
        return tasks.save(t);
    }

    @Transactional
    public ProblemReport reportProblem(TaskRequests.ProblemRequest request) {
        HospioUserDetails user = CurrentUser.require();
        String repairId = maintenance.openRepair(request.roomId(), request.roomNumber(), request.description(), request.photoKey());
        ProblemReport report = problems.save(ProblemReport.builder().roomId(request.roomId()).roomNumber(request.roomNumber())
                .description(request.description()).photoKey(request.photoKey()).repairId(repairId).reportedBy(user.getUserId())
                .status(repairId == null ? "PENDING" : "SENT").build());
        events.publish(KafkaTopics.PROBLEM_REPORTED, ProblemReportedEvent.builder().reportId(report.getId()).roomId(request.roomId())
                .roomNumber(request.roomNumber()).description(request.description()).repairId(repairId).build());
        return report;
    }

    @Transactional
    public LinenRequest askForLinen(TaskRequests.LinenRequestDto request) {
        LinenRequest r = linenRequests.save(LinenRequest.builder().roomId(request.roomId()).roomNumber(request.roomNumber())
                .items(request.items()).requestedBy(CurrentUser.require().getUserId()).build());
        events.publish(KafkaTopics.LINEN_REQUESTED, LinenRequestedEvent.builder().requestId(r.getId()).roomId(r.getRoomId())
                .roomNumber(r.getRoomNumber()).items(r.getItems()).build());
        return r;
    }

    @Transactional(readOnly = true)
    public List<LinenRequest> openLinenRequests() {
        return linenRequests.findByHotelIdAndStatusOrderByCreatedAt(TenantContext.requireHotel(), "REQUESTED");
    }

    @Transactional
    public LinenRequest linenSent(String id) {
        LinenRequest r = linenRequests.findById(id).orElseThrow(() -> new ResourceNotFoundException("Request not found"));
        r.setStatus("SENT");
        return linenRequests.save(r);
    }

    /** Minibar restock: what was taken is charged to the guest's folio as one line. */
    @Transactional
    public MinibarRestock minibar(TaskRequests.MinibarRequest request) {
        HospioUserDetails user = CurrentUser.require();
        ReservationClient.ActiveStay stay = reservations.requireActiveStay(request.roomId());
        MinibarRestock m = minibars.save(MinibarRestock.builder().roomId(request.roomId()).roomNumber(stay.roomNumber())
                .bookingId(stay.bookingId()).items(request.items()).total(Money.of(request.total())).charged(true)
                .restockedBy(user.getUserId()).build());
        if (m.getTotal().signum() > 0) {
            events.publish(KafkaTopics.CHARGE_POSTED, ChargePostedEvent.builder().bookingId(stay.bookingId()).roomId(request.roomId())
                    .roomNumber(stay.roomNumber()).sourceModule("MINIBAR").sourceRef("MINIBAR-" + m.getId()).description("Minibar")
                    .amount(m.getTotal()).taxAmount(java.math.BigDecimal.ZERO).category("MINIBAR").build());
        }
        return m;
    }

    @Transactional(readOnly = true)
    public List<LostFoundItem> lostAndFound() {
        return lostFound.findByHotelIdOrderByFoundAtDesc(TenantContext.requireHotel());
    }

    @Transactional
    public LostFoundItem logFound(TaskRequests.LostFoundRequest request) {
        return lostFound.save(LostFoundItem.builder().description(request.description()).foundWhere(request.foundWhere())
                .roomId(request.roomId()).photoKey(request.photoKey()).foundBy(CurrentUser.require().getUserId()).build());
    }

    @Transactional
    public LostFoundItem handBack(String id, TaskRequests.ReturnItemRequest request) {
        LostFoundItem item = lostFound.findById(id).orElseThrow(() -> new ResourceNotFoundException("Item not found"));
        item.setStatus(LostFoundItem.Status.RETURNED);
        item.setReturnedTo(request.returnedTo());
        item.setReturnedAt(Instant.now());
        return lostFound.save(item);
    }

    void setRoomStatus(String roomId, String roomNumber, RoomStatus.Cleanliness status, String by) {
        RoomStatus rs = roomStatuses.findByRoomId(roomId).orElseGet(() -> RoomStatus.builder().roomId(roomId).roomNumber(roomNumber).build());
        rs.setStatus(status);
        rs.setChangedBy(by);
        if (roomNumber != null) {
            rs.setRoomNumber(roomNumber);
        }
        roomStatuses.save(rs);
    }

    private CleaningTask task(String id) {
        return tasks.findById(id).orElseThrow(() -> new ResourceNotFoundException("Task not found"));
    }

    private static void requireSupervisor() {
        HospioUserDetails user = CurrentUser.require();
        if (!user.isManagerOrOwner() && !user.has(com.hospio.common.enums.Role.HOUSEKEEPING, com.hospio.common.enums.AccessLevel.FULL)) {
            throw new ForbiddenException("Only a housekeeping supervisor can do this");
        }
    }
}
