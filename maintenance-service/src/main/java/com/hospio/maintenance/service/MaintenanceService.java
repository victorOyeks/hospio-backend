package com.hospio.maintenance.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hospio.common.exception.BadRequestException;
import com.hospio.common.exception.ForbiddenException;
import com.hospio.common.exception.ResourceNotFoundException;
import com.hospio.common.integrations.ObjectStorage;
import com.hospio.common.kafka.EventPublisher;
import com.hospio.common.kafka.KafkaTopics;
import com.hospio.common.kafka.events.NoticeEvent;
import com.hospio.common.kafka.events.RepairDoneEvent;
import com.hospio.common.kafka.events.RepairOpenedEvent;
import com.hospio.common.security.CurrentUser;
import com.hospio.common.security.HospioUserDetails;
import com.hospio.common.tenancy.Tenant;
import com.hospio.common.tenancy.TenantContext;
import com.hospio.common.util.Money;
import com.hospio.maintenance.client.InventoryClient;
import com.hospio.maintenance.client.PropertyClient;
import com.hospio.maintenance.client.ReservationClient;
import com.hospio.maintenance.dto.MaintenanceRequests;
import com.hospio.maintenance.dto.MaintenanceViews;
import com.hospio.maintenance.entity.Asset;
import com.hospio.maintenance.entity.Repair;
import com.hospio.maintenance.entity.RepairUpdate;
import com.hospio.maintenance.entity.ScheduledJob;
import com.hospio.maintenance.entity.ScheduledJobRun;
import com.hospio.maintenance.repository.AssetRepository;
import com.hospio.maintenance.repository.RepairRepository;
import com.hospio.maintenance.repository.RepairUpdateRepository;
import com.hospio.maintenance.repository.ScheduledJobRepository;
import com.hospio.maintenance.repository.ScheduledJobRunRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

/**
 * Work orders, rooms out of order, assets and preventive schedules (docs/laundry-maintenance.md). Rules live in {@link WorkOrderRules},
 * who-may in {@link WorkOrderAccess}; this class applies them, moves parts out of the Maintenance store and tells people.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MaintenanceService {

    static final ZoneId LAGOS = ZoneId.of("Africa/Lagos");
    private static final long MAX_PHOTO = 2L * 1024 * 1024;

    private final RepairRepository repairs;
    private final RepairUpdateRepository updates;
    private final ScheduledJobRepository jobs;
    private final ScheduledJobRunRepository runs;
    private final AssetRepository assets;
    private final PropertyClient property;
    private final ReservationClient reservations;
    private final InventoryClient inventory;
    private final EventPublisher events;
    private final ObjectMapper mapper;
    private final ObjectStorage storage;
    private final PlatformTransactionManager transactionManager;

    // ---- reading -------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<MaintenanceViews.RepairView> list(String scope, String roomId) {
        HospioUserDetails u = CurrentUser.require();
        String hotel = TenantContext.requireHotel();
        String s = scope == null ? "open" : scope.toLowerCase(Locale.ROOT);
        List<Repair> rows = switch (s) {
            case "mine" -> repairs.findByHotelIdAndStatusInOrderByCreatedAtDesc(hotel, WorkOrderRules.OPEN).stream()
                    .filter(r -> u.getUserId().equals(r.getAssignedTo())).toList();
            // No cap (docs/pagination.md): screens page done and all through RepairLists; this unpaged form is for internal callers.
            case "done" -> repairs.findByHotelIdAndStatusInOrderByCreatedAtDesc(hotel, java.util.EnumSet.complementOf(java.util.EnumSet.copyOf(WorkOrderRules.OPEN)));
            case "all" -> repairs.findByHotelIdOrderByCreatedAtDesc(hotel);
            case "open" -> repairs.findByHotelIdAndStatusInOrderByCreatedAtDesc(hotel, WorkOrderRules.OPEN);
            default -> throw new BadRequestException("Pick a list: mine, open, done or all");
        };
        Instant now = Instant.now();
        Map<String, Asset> am = assetMap(hotel);
        return rows.stream().filter(r -> roomId == null || roomId.equals(r.getRoomId())).sorted(WorkOrderRules.boardOrder(now))
                .map(r -> view(r, am, null, now)).toList();
    }

    @Transactional(readOnly = true)
    public MaintenanceViews.RepairView detail(String id) {
        Repair r = find(id);
        List<MaintenanceViews.UpdateView> ups = updates.findByRepairIdOrderByCreatedAt(id).stream()
                .map(x -> new MaintenanceViews.UpdateView(x.getId(), x.getCreatedAt(), x.getByName(), x.getNote())).toList();
        return view(r, assetMap(r.getHotelId()), ups, Instant.now());
    }

    // ---- raising -------------------------------------------------------------------------------------

    @Transactional
    public MaintenanceViews.RepairView open(MaintenanceRequests.RepairRequest request, boolean internal) {
        HospioUserDetails u = CurrentUser.get().orElse(null);
        String hotel = TenantContext.requireHotel();
        String roomId = blank(request.roomId());
        String area = blank(request.area());
        String roomNumber = blank(request.roomNumber());
        if (roomId != null) {
            PropertyClient.Room room = property.room(roomId);
            roomNumber = room.number();
        } else if (area == null) {
            throw new BadRequestException("Pick a room or say where it is");
        }
        Repair.Category category = category(request.category(), internal ? Repair.Category.OTHER : null);
        Repair.Priority priority = request.priority() == null ? Repair.Priority.NORMAL : request.priority();
        Instant now = Instant.now();
        Repair r = Repair.builder().number(nextNumber(hotel)).roomId(roomId).roomNumber(roomNumber).area(area).title(request.title().trim())
                .description(blank(request.description())).priority(priority).category(category)
                .source(request.source() == null ? (u == null ? "SYSTEM" : WorkOrderAccess.raiserRole(u)) : request.source())
                .photoKey(blank(request.photoKey())).reportedBy(u == null ? null : u.getUserId()).reportedByName(u == null ? null : u.getFullName())
                .reportedByRole(u == null ? null : WorkOrderAccess.raiserRole(u)).dueAt(WorkOrderRules.dueAt(priority, now)).build();
        String to = blank(request.assignedTo());
        if (to != null && u != null) {
            if (!WorkOrderAccess.canAssign(u, r, to)) {
                throw new ForbiddenException("Giving a work order to someone is for the maintenance supervisor or a manager");
            }
            r.setAssignedTo(to);
            r.setAssignedToName(blank(request.assignedToName()));
            r.setStatus(Repair.Status.ASSIGNED);
        }
        repairs.save(r);
        events.publish(KafkaTopics.REPAIR_OPENED, RepairOpenedEvent.builder().repairId(r.getId()).roomId(r.getRoomId())
                .roomNumber(r.getRoomNumber()).area(r.getArea()).title(r.getNumber() + " · " + r.getTitle()).priority(r.getPriority().name()).build());
        if (priority == Repair.Priority.URGENT) {
            notice(r, "REPAIR", "Urgent work order " + r.getNumber(), where(r) + " · " + r.getTitle(), null, List.of("MANAGER"));
        }
        if (r.getAssignedTo() != null && (u == null || !r.getAssignedTo().equals(u.getUserId()))) {
            notice(r, "REPAIR", "Work order for you · " + r.getNumber(), where(r) + " · " + r.getTitle(), List.of(r.getAssignedTo()), null);
        }
        return view(r, Map.of(), null, now);
    }

    /**
     * Housekeeping's "Report a problem" (service to service, the reporter's token forwarded): the work order is opened with the
     * reporter's priority and, with {@code outOfOrder}, the room goes out of order at once. A room with a guest checked in is refused
     * before anything is opened. An urgent problem in a room is told to the front office; a room taken out of order is told by
     * property-service's own notice (front office, housekeeping, managers).
     */
    @Transactional
    public MaintenanceViews.RepairView openReported(MaintenanceRequests.RepairRequest request, boolean outOfOrder) {
        HospioUserDetails u = CurrentUser.get().orElse(null);
        String roomId = blank(request.roomId());
        if (outOfOrder) {
            if (roomId == null) {
                throw new BadRequestException("Only a room can go out of order · pick the room");
            }
            if (u != null && !WorkOrderAccess.canReportOutOfOrder(u)) {
                throw new ForbiddenException("Taking a room out of order is for housekeeping, maintenance or a manager");
            }
            Optional<String> guest = reservations.guestIn(roomId);
            if (guest.isPresent()) {
                String number = blank(request.roomNumber());
                if (number == null) {
                    number = property.room(roomId).number();
                }
                throw new BadRequestException(WorkOrderRules.guestInRefusal(number, guest.get()));
            }
        }
        MaintenanceViews.RepairView opened = open(request, true);
        Repair r = find(opened.id());
        if (outOfOrder) {
            String reason = r.getNumber() + " · " + r.getTitle();
            property.outOfOrder(r.getRoomId(), reason, null);
            r.setRoomOutOfOrder(true);
            r.setOooUntil(null);
            repairs.save(r);
            if (u != null) {
                log(r, u, "Room " + r.getRoomNumber() + " out of order · reported by housekeeping");
            }
        }
        List<String> roles = WorkOrderRules.reportedProblemRoles(r.getPriority() == Repair.Priority.URGENT, outOfOrder, r.getRoomId());
        if (!roles.isEmpty()) {
            String title = outOfOrder ? "Room " + r.getRoomNumber() + " is out of order" : "Urgent problem in room " + r.getRoomNumber();
            String body = r.getTitle() + " · " + r.getNumber() + (outOfOrder ? " · don't check anyone in there until maintenance clears it" : "");
            events.publish(KafkaTopics.NOTICE, NoticeEvent.builder().kind("REPAIR").title(title).body(body).link("/dashboard/rooms")
                    .roles(roles).build());
        }
        return outOfOrder ? detail(r.getId()) : opened;
    }

    // ---- running -------------------------------------------------------------------------------------

    @Transactional
    public MaintenanceViews.RepairView assign(String id, MaintenanceRequests.AssignRequest request) {
        HospioUserDetails u = CurrentUser.require();
        Repair r = find(id);
        WorkOrderRules.check(WorkOrderRules.Action.ASSIGN, r.getStatus(), r.getNumber());
        if (!WorkOrderAccess.canAssign(u, r, request.assignedTo())) {
            throw new ForbiddenException(WorkOrderAccess.technician(u) ? "You can take a work order for yourself; giving it to someone else is for the supervisor or a manager"
                    : "Giving a work order to someone is for the maintenance supervisor or a manager");
        }
        boolean changed = !request.assignedTo().equals(r.getAssignedTo());
        r.setAssignedTo(request.assignedTo());
        r.setAssignedToName(blank(request.assignedToName()) == null ? (request.assignedTo().equals(u.getUserId()) ? u.getFullName() : null)
                : request.assignedToName().trim());
        if (r.getStatus() == Repair.Status.OPEN) {
            r.setStatus(Repair.Status.ASSIGNED);
        }
        repairs.save(r);
        if (changed) {
            log(r, u, "Assigned to " + nz(r.getAssignedToName(), "a technician"));
            if (!r.getAssignedTo().equals(u.getUserId())) {
                notice(r, "REPAIR", "Work order for you · " + r.getNumber(), where(r) + " · " + r.getTitle(), List.of(r.getAssignedTo()), null);
            }
        }
        return detail(r.getId());
    }

    @Transactional
    public MaintenanceViews.RepairView start(String id) {
        HospioUserDetails u = CurrentUser.require();
        Repair r = find(id);
        WorkOrderRules.check(WorkOrderRules.Action.START, r.getStatus(), r.getNumber());
        if (r.getAssignedTo() == null && WorkOrderAccess.technician(u)) {
            r.setAssignedTo(u.getUserId());
            r.setAssignedToName(u.getFullName());
        }
        requireRun(u, r);
        r.setStatus(Repair.Status.IN_PROGRESS);
        if (r.getStartedAt() == null) {
            r.setStartedAt(Instant.now());
        }
        repairs.save(r);
        log(r, u, "Started work");
        return detail(r.getId());
    }

    @Transactional
    public MaintenanceViews.RepairView hold(String id, MaintenanceRequests.ReasonRequest request) {
        HospioUserDetails u = CurrentUser.require();
        Repair r = find(id);
        String reason = reason(request, "Say why it is on hold");
        WorkOrderRules.check(WorkOrderRules.Action.HOLD, r.getStatus(), r.getNumber());
        requireRun(u, r);
        r.setStatus(Repair.Status.ON_HOLD);
        r.setHoldReason(reason);
        repairs.save(r);
        log(r, u, "On hold: " + reason);
        notice(r, "REPAIR", r.getNumber() + " on hold", where(r) + " · " + reason, raiser(r, u), null);
        return detail(r.getId());
    }

    @Transactional
    public MaintenanceViews.RepairView resume(String id) {
        HospioUserDetails u = CurrentUser.require();
        Repair r = find(id);
        WorkOrderRules.check(WorkOrderRules.Action.RESUME, r.getStatus(), r.getNumber());
        requireRun(u, r);
        r.setStatus(Repair.Status.IN_PROGRESS);
        r.setHoldReason(null);
        repairs.save(r);
        log(r, u, "Back on it");
        return detail(r.getId());
    }

    @Transactional
    public MaintenanceViews.RepairView complete(String id, MaintenanceRequests.CompleteRequest request) {
        HospioUserDetails u = CurrentUser.require();
        Repair r = find(id);
        WorkOrderRules.check(WorkOrderRules.Action.COMPLETE, r.getStatus(), r.getNumber());
        requireRun(u, r);
        String note = request == null ? null : blank(request.note());
        if (note == null) {
            throw new BadRequestException("Say what was done");
        }
        if (note.length() > 1000) {
            throw new BadRequestException("Keep the note under 1000 characters");
        }
        if (request.labourMinutes() != null && (request.labourMinutes() < 0 || request.labourMinutes() > 10_000)) {
            throw new BadRequestException("Labour time is in minutes, between 0 and 10000");
        }
        BigDecimal vendorCost = request.vendorCost() == null ? BigDecimal.ZERO : Money.of(request.vendorCost());
        if (vendorCost.signum() < 0) {
            throw new BadRequestException("The vendor cost can't be negative");
        }
        String vendor = blank(request.vendorName());
        if (vendorCost.signum() > 0 && vendor == null) {
            throw new BadRequestException("Name the vendor you paid");
        }
        List<MaintenanceViews.PartView> drawn = new ArrayList<>(parts(r));
        List<Map<String, Object>> lines = new ArrayList<>();
        if (request.parts() != null) {
            for (MaintenanceRequests.PartLine p : request.parts()) {
                if (p == null || blank(p.itemId()) == null || p.quantity() == null) {
                    throw new BadRequestException("Pick a part and a quantity on every line");
                }
                if (p.quantity().signum() <= 0) {
                    throw new BadRequestException("Enter a quantity above zero for every part");
                }
                lines.add(Map.of("itemId", p.itemId(), "quantity", p.quantity()));
            }
        }
        if (!lines.isEmpty()) {
            String reference = r.getReopenCount() == 0 ? r.getNumber() : r.getNumber() + "-" + (r.getReopenCount() + 1);
            for (InventoryClient.Part p : inventory.useParts(reference, "Used on " + r.getNumber() + " · " + where(r), lines)) {
                drawn.add(new MaintenanceViews.PartView(p.itemId(), p.name(), p.unit(), p.quantity(), p.unitCost(), p.value()));
            }
        }
        r.setParts(write(drawn));
        r.setPartsCost(Money.of(drawn.stream().map(MaintenanceViews.PartView::value).reduce(BigDecimal.ZERO, BigDecimal::add)));
        boolean again = r.getReopenCount() > 0;
        if (vendor != null || !again) {
            r.setVendorName(vendor);
        }
        r.setVendorCost(Money.of((again ? z(r.getVendorCost()) : BigDecimal.ZERO).add(vendorCost)));
        int minutes = request.labourMinutes() == null ? 0 : request.labourMinutes();
        r.setLabourMinutes((again && r.getLabourMinutes() != null ? r.getLabourMinutes() : 0) + minutes);
        r.setCompletionNote(note);
        r.setStatus(Repair.Status.DONE);
        r.setHoldReason(null);
        r.setDoneAt(Instant.now());
        r.setDoneBy(u.getUserId());
        boolean backInService = r.isRoomOutOfOrder() && r.getRoomId() != null;
        if (backInService) {
            property.backInService(r.getRoomId());
            r.setRoomOutOfOrder(false);
            r.setOooUntil(null);
        }
        repairs.save(r);
        log(r, u, "Done: " + note);
        if (r.getScheduleId() != null) {
            jobs.findById(r.getScheduleId()).ifPresent(j -> {
                j.setLastDone(LocalDate.now(LAGOS));
                jobs.save(j);
                runs.save(ScheduledJobRun.builder().jobId(j.getId()).doneOn(LocalDate.now(LAGOS)).note(note).doneBy(u.getUserId()).build());
            });
        }
        events.publish(KafkaTopics.REPAIR_DONE, RepairDoneEvent.builder().repairId(r.getId()).roomId(r.getRoomId()).roomNumber(r.getRoomNumber())
                .title(r.getTitle()).doneBy(u.getFullName()).build());
        notice(r, "REPAIR_DONE", r.getNumber() + " done", where(r) + " · " + note + (backInService ? " · room back in service" : ""), raiser(r, u), null);
        return detail(r.getId());
    }

    @Transactional
    public MaintenanceViews.RepairView reopen(String id, MaintenanceRequests.ReasonRequest request) {
        HospioUserDetails u = CurrentUser.require();
        Repair r = find(id);
        String reason = reason(request, "Say why it needs another look");
        WorkOrderRules.check(WorkOrderRules.Action.REOPEN, r.getStatus(), r.getNumber());
        if (!WorkOrderAccess.canReopen(u, r)) {
            throw new ForbiddenException("Only whoever raised " + r.getNumber() + " or a manager can reopen it");
        }
        r.setStatus(WorkOrderRules.afterReopen(r.getAssignedTo()));
        r.setReopenCount(r.getReopenCount() + 1);
        r.setReopenReason(reason);
        r.setDoneAt(null);
        r.setDoneBy(null);
        r.setOverdueNotified(false);
        r.setDueAt(WorkOrderRules.dueAt(r.getPriority(), Instant.now()));
        repairs.save(r);
        log(r, u, "Reopened: " + reason);
        notice(r, "REPAIR", r.getNumber() + " reopened", where(r) + " · " + reason, r.getAssignedTo() == null ? null : List.of(r.getAssignedTo()),
                r.getAssignedTo() == null ? List.of("MAINTENANCE") : null);
        return detail(r.getId());
    }

    @Transactional
    public MaintenanceViews.RepairView cancel(String id, MaintenanceRequests.ReasonRequest request) {
        HospioUserDetails u = CurrentUser.require();
        Repair r = find(id);
        String reason = reason(request, "Say why it is cancelled");
        WorkOrderRules.check(WorkOrderRules.Action.CANCEL, r.getStatus(), r.getNumber());
        if (!WorkOrderAccess.canCancel(u, r)) {
            throw new ForbiddenException(u.getUserId().equals(r.getReportedBy()) ? r.getNumber() + " has started · ask the maintenance supervisor or a manager to cancel it"
                    : "Cancelling a work order is for whoever raised it, the maintenance supervisor or a manager");
        }
        r.setStatus(Repair.Status.CANCELLED);
        r.setCancelReason(reason);
        r.setCancelledAt(Instant.now());
        if (r.isRoomOutOfOrder() && r.getRoomId() != null) {
            property.backInService(r.getRoomId());
            r.setRoomOutOfOrder(false);
            r.setOooUntil(null);
        }
        repairs.save(r);
        log(r, u, "Cancelled: " + reason);
        List<String> tell = new ArrayList<>();
        raiserList(r, u).forEach(tell::add);
        if (r.getAssignedTo() != null && !r.getAssignedTo().equals(u.getUserId())) {
            tell.add(r.getAssignedTo());
        }
        notice(r, "REPAIR", r.getNumber() + " cancelled", where(r) + " · " + reason, tell, null);
        return detail(r.getId());
    }

    @Transactional
    public MaintenanceViews.RepairView addUpdate(String id, MaintenanceRequests.UpdateRequest request) {
        HospioUserDetails u = CurrentUser.require();
        if (u.isReadOnly()) {
            throw new ForbiddenException("Read-only access can't add updates");
        }
        Repair r = find(id);
        WorkOrderRules.check(WorkOrderRules.Action.UPDATE, r.getStatus(), r.getNumber());
        log(r, u, request.note().trim());
        return detail(r.getId());
    }

    @Transactional
    public MaintenanceViews.RepairView outOfOrder(String id, MaintenanceRequests.OutOfOrderRequest request) {
        HospioUserDetails u = CurrentUser.require();
        Repair r = find(id);
        if (!WorkOrderAccess.canTakeOutOfOrder(u)) {
            throw new ForbiddenException("Taking a room out of order is for maintenance or a manager");
        }
        if (r.getRoomId() == null) {
            throw new BadRequestException(r.getNumber() + " isn't for a room");
        }
        WorkOrderRules.check(WorkOrderRules.Action.OUT_OF_ORDER, r.getStatus(), r.getNumber());
        String reason = request == null ? null : blank(request.reason());
        if (reason == null) {
            reason = r.getTitle();
        }
        LocalDate until = request == null ? null : request.until();
        if (until != null && until.isBefore(LocalDate.now(LAGOS))) {
            throw new BadRequestException("The end date can't be in the past");
        }
        if (r.isRoomOutOfOrder()) {
            throw new BadRequestException("Room " + r.getRoomNumber() + " is already out of order on " + r.getNumber());
        }
        Optional<String> guest = reservations.guestIn(r.getRoomId());
        if (guest.isPresent()) {
            throw new BadRequestException("Room " + r.getRoomNumber() + " has a guest checked in (" + guest.get() + ") · move them to another room first");
        }
        property.outOfOrder(r.getRoomId(), r.getNumber() + " · " + reason, until);
        r.setRoomOutOfOrder(true);
        r.setOooUntil(until);
        repairs.save(r);
        log(r, u, "Room " + r.getRoomNumber() + " out of order" + (until == null ? "" : " until " + until));
        return detail(r.getId());
    }

    @Transactional
    public MaintenanceViews.RepairView backInService(String id) {
        HospioUserDetails u = CurrentUser.require();
        Repair r = find(id);
        if (!WorkOrderAccess.canTakeOutOfOrder(u)) {
            throw new ForbiddenException("Putting a room back in service is for maintenance or a manager");
        }
        if (r.getRoomId() == null) {
            throw new BadRequestException(r.getNumber() + " isn't for a room");
        }
        if (!r.isRoomOutOfOrder()) {
            throw new BadRequestException("Room " + r.getRoomNumber() + " isn't out of order on " + r.getNumber());
        }
        property.backInService(r.getRoomId());
        r.setRoomOutOfOrder(false);
        r.setOooUntil(null);
        repairs.save(r);
        log(r, u, "Room " + r.getRoomNumber() + " back in service");
        return detail(r.getId());
    }

    // ---- photo ---------------------------------------------------------------------------------------

    @Transactional
    public MaintenanceViews.RepairView photo(String id, MultipartFile file) {
        HospioUserDetails u = CurrentUser.require();
        if (u.isReadOnly()) {
            throw new ForbiddenException("Read-only access can't add a photo");
        }
        Repair r = find(id);
        if (file == null || file.isEmpty()) {
            throw new BadRequestException("Pick a photo");
        }
        String type = file.getContentType() == null ? "" : file.getContentType().toLowerCase(Locale.ROOT);
        if (!List.of("image/jpeg", "image/png", "image/webp").contains(type)) {
            throw new BadRequestException("The photo must be a JPEG, PNG or WebP image");
        }
        if (file.getSize() > MAX_PHOTO) {
            throw new BadRequestException("The photo is too big · keep it under 2 MB");
        }
        String key = "maintenance/" + r.getBusinessId() + "/" + LocalDate.now(LAGOS).getYear() + "/" + r.getId() + "." + type.substring(6);
        try {
            storage.put(key, file.getBytes(), type);
        } catch (Exception e) {
            log.warn("Could not store the photo for {}: {}", r.getNumber(), e.getMessage());
            throw new BadRequestException("Couldn't save the photo right now · try again");
        }
        r.setPhotoKey(key);
        repairs.save(r);
        log(r, u, "Added a photo");
        return detail(r.getId());
    }

    public record Photo(byte[] bytes, String contentType) {
    }

    @Transactional(readOnly = true)
    public Photo photo(String id) {
        Repair r = find(id);
        if (r.getPhotoKey() == null) {
            throw new ResourceNotFoundException("No photo on " + r.getNumber());
        }
        byte[] bytes = storage.get(r.getPhotoKey()).orElseThrow(() -> new ResourceNotFoundException("The photo on " + r.getNumber() + " is missing"));
        String key = r.getPhotoKey();
        String type = key.endsWith(".png") ? "image/png" : key.endsWith(".webp") ? "image/webp" : "image/jpeg";
        return new Photo(bytes, type);
    }

    // ---- money ---------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public MaintenanceViews.Summary summary(LocalDate from, LocalDate to) {
        String hotel = TenantContext.requireHotel();
        LocalDate t = to == null ? LocalDate.now(LAGOS) : to;
        LocalDate f = from == null ? t.minusDays(29) : from;
        if (f.isAfter(t)) {
            throw new BadRequestException("The start date is after the end date");
        }
        List<Repair> done = repairs.findByHotelIdAndStatusAndDoneAtBetween(hotel, Repair.Status.DONE, f.atStartOfDay(LAGOS).toInstant(),
                t.plusDays(1).atStartOfDay(LAGOS).toInstant());
        List<Repair> open = repairs.findByHotelIdAndStatusInOrderByCreatedAtDesc(hotel, WorkOrderRules.OPEN);
        Instant now = Instant.now();
        BigDecimal parts = done.stream().map(Repair::getPartsCost).map(MaintenanceService::z).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal vendor = done.stream().map(Repair::getVendorCost).map(MaintenanceService::z).reduce(BigDecimal.ZERO, BigDecimal::add);
        long minutes = done.stream().mapToLong(r -> r.getLabourMinutes() == null ? 0 : r.getLabourMinutes()).sum();
        Map<Repair.Category, List<Repair>> byCat = done.stream().collect(Collectors.groupingBy(Repair::getCategory, LinkedHashMap::new, Collectors.toList()));
        List<MaintenanceViews.CategoryCost> cats = byCat.entrySet().stream().map(e -> new MaintenanceViews.CategoryCost(e.getKey().name(), e.getValue().size(),
                Money.of(e.getValue().stream().map(r -> z(r.getPartsCost()).add(z(r.getVendorCost()))).reduce(BigDecimal.ZERO, BigDecimal::add)))).toList();
        return new MaintenanceViews.Summary(f, t, done.size(), open.size(), open.stream().filter(r -> WorkOrderRules.overdue(r.getStatus(), r.getDueAt(), now)).count(),
                Money.of(parts), Money.of(vendor), minutes, Money.of(parts.add(vendor)), cats);
    }

    // ---- assets --------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<MaintenanceViews.AssetView> assets() {
        String hotel = TenantContext.requireHotel();
        Map<String, Long> open = repairs.findByHotelIdAndStatusInOrderByCreatedAtDesc(hotel, WorkOrderRules.OPEN).stream()
                .filter(r -> r.getAssetId() != null).collect(Collectors.groupingBy(Repair::getAssetId, Collectors.counting()));
        return assets.findByHotelIdOrderByName(hotel).stream().map(a -> assetView(a, open.getOrDefault(a.getId(), 0L))).toList();
    }

    @Transactional
    public MaintenanceViews.AssetView saveAsset(String id, MaintenanceRequests.AssetRequest request) {
        requireSupervisor();
        String hotel = TenantContext.requireHotel();
        String name = request == null ? null : blank(request.name());
        if (name == null) {
            throw new BadRequestException("Name the asset");
        }
        if (name.length() > 150) {
            throw new BadRequestException("Keep the name under 150 characters");
        }
        Asset a = id == null ? new Asset() : assets.findById(id).orElseThrow(() -> new ResourceNotFoundException("Asset not found"));
        boolean taken = assets.findByHotelIdOrderByName(hotel).stream().anyMatch(x -> !x.getId().equals(a.getId()) && x.getName().equalsIgnoreCase(name));
        if (taken) {
            throw new BadRequestException("There's already an asset called " + name);
        }
        a.setName(name);
        a.setCategory(category(request.category(), Repair.Category.OTHER));
        String roomId = blank(request.roomId());
        if (roomId != null) {
            a.setRoomId(roomId);
            a.setRoomNumber(property.room(roomId).number());
            a.setArea(null);
        } else {
            a.setRoomId(null);
            a.setRoomNumber(null);
            a.setArea(blank(request.area()));
        }
        if (a.getRoomId() == null && a.getArea() == null) {
            throw new BadRequestException("Say where the asset is: a room or an area");
        }
        if (request.active() != null) {
            a.setActive(request.active());
        }
        assets.save(a);
        return assetView(a, 0);
    }

    // ---- preventive schedules ------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<MaintenanceViews.JobView> scheduledJobs() {
        String hotel = TenantContext.requireHotel();
        Map<String, Asset> am = assetMap(hotel);
        return jobs.findByHotelIdAndDeletedFalseOrderByNextDue(hotel).stream().map(j -> jobView(j, am)).toList();
    }

    @Transactional
    public MaintenanceViews.JobView saveJob(String id, MaintenanceRequests.ScheduledJobRequest request) {
        requireSupervisor();
        String hotel = TenantContext.requireHotel();
        ScheduledJob j = id == null ? new ScheduledJob() : job(id);
        String title = request == null ? null : blank(request.title());
        if (title == null) {
            throw new BadRequestException("Name the job, e.g. AC service");
        }
        if (title.length() > 150) {
            throw new BadRequestException("Keep the title under 150 characters");
        }
        int every = request.everyDays() == null ? 0 : request.everyDays();
        if (every < 1 || every > 730) {
            throw new BadRequestException("Repeat every 1 to 730 days");
        }
        LocalDate today = LocalDate.now(LAGOS);
        LocalDate next = request.nextDue() != null ? request.nextDue() : id == null ? today.plusDays(every) : j.getNextDue();
        if (next.isBefore(today) && (id == null || request.nextDue() != null)) {
            throw new BadRequestException("The next due date can't be in the past");
        }
        j.setTitle(title);
        j.setDescription(blank(request.description()));
        j.setCategory(category(request.category(), Repair.Category.OTHER));
        j.setPriority(request.priority() == null ? Repair.Priority.NORMAL : request.priority());
        j.setEveryDays(every);
        j.setNextDue(next);
        String assetId = blank(request.assetId());
        String roomId = blank(request.roomId());
        if (assetId != null) {
            Asset a = assets.findById(assetId).orElseThrow(() -> new BadRequestException("That asset isn't in this hotel"));
            j.setAssetId(a.getId());
            j.setRoomId(a.getRoomId());
            j.setRoomNumber(a.getRoomNumber());
            j.setArea(a.getArea());
        } else if (roomId != null) {
            j.setAssetId(null);
            j.setRoomId(roomId);
            j.setRoomNumber(property.room(roomId).number());
            j.setArea(null);
        } else {
            j.setAssetId(null);
            j.setRoomId(null);
            j.setRoomNumber(null);
            j.setArea(blank(request.area()));
            if (j.getArea() == null) {
                throw new BadRequestException("Pick an asset or a room, or say where the job is");
            }
        }
        j.setAssignedTo(blank(request.assignedTo()));
        j.setAssignedToName(j.getAssignedTo() == null ? null : blank(request.assignedToName()));
        if (request.paused() != null) {
            j.setPaused(request.paused());
        }
        jobs.save(j);
        return jobView(j, assetMap(hotel));
    }

    @Transactional
    public MaintenanceViews.JobView pauseJob(String id, boolean paused) {
        requireSupervisor();
        ScheduledJob j = job(id);
        j.setPaused(paused);
        jobs.save(j);
        return jobView(j, assetMap(j.getHotelId()));
    }

    @Transactional
    public void deleteJob(String id) {
        requireSupervisor();
        ScheduledJob j = job(id);
        j.setDeleted(true);
        jobs.save(j);
    }

    @Transactional(readOnly = true)
    public List<ScheduledJobRun> pastRuns(String id) {
        return runs.findByJobIdOrderByDoneOnDesc(job(id).getId());
    }

    /** "Run now" for a manager: the same job as the morning one, for this hotel only. */
    public MaintenanceViews.RunResult runDueNow() {
        String hotel = TenantContext.requireHotel();
        LocalDate today = LocalDate.now(LAGOS);
        int opened = 0;
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        for (ScheduledJob j : new TransactionTemplate(transactionManager).execute(s ->
                jobs.findByHotelIdAndDeletedFalseAndPausedFalseAndNextDueLessThanEqual(hotel, today))) {
            Boolean ok = tx.execute(s -> openFromSchedule(j.getId(), today));
            opened += Boolean.TRUE.equals(ok) ? 1 : 0;
        }
        return new MaintenanceViews.RunResult(opened);
    }

    /** Every morning at 06:00 Lagos, schedules that fell due open a work order each. Idempotent per (schedule, due date). */
    @Scheduled(cron = "0 0 6 * * *", zone = "Africa/Lagos")
    public void openDueScheduledJobs() {
        LocalDate today = LocalDate.now(LAGOS);
        TenantContext.runWith(Tenant.system("preventive-maintenance"), () -> {
            TransactionTemplate tx = new TransactionTemplate(transactionManager);
            List<ScheduledJob> due = tx.execute(s -> jobs.findByDeletedFalseAndPausedFalseAndNextDueLessThanEqual(today));
            int opened = 0;
            for (ScheduledJob j : due == null ? List.<ScheduledJob>of() : due) {
                try {
                    Boolean ok = tx.execute(s -> openFromSchedule(j.getId(), today));
                    opened += Boolean.TRUE.equals(ok) ? 1 : 0;
                } catch (RuntimeException e) {
                    log.warn("Preventive job {} at hotel {} did not open a work order: {}", j.getTitle(), j.getHotelId(), e.getMessage());
                }
            }
            if (opened > 0) {
                log.info("Preventive maintenance opened {} work orders", opened);
            }
        });
    }

    /**
     * Opens the work order for a schedule's current due date and moves the due date on. Returns false when there was nothing to open
     * (paused, deleted, not due, or already opened for that date). Must run inside a transaction.
     */
    boolean openFromSchedule(String jobId, LocalDate today) {
        ScheduledJob j = jobs.findById(jobId).orElse(null);
        if (j == null || j.isDeleted() || j.isPaused() || j.getNextDue().isAfter(today)) {
            return false;
        }
        repairs.lock("wo-schedule:" + j.getId());
        LocalDate due = j.getNextDue();
        boolean opened = false;
        if (!repairs.existsByScheduleIdAndScheduleDue(j.getId(), due)) {
            Instant now = Instant.now();
            Repair r = Repair.builder().businessId(j.getBusinessId()).hotelId(j.getHotelId()).number(nextNumber(j.getHotelId())).title(j.getTitle())
                    .description(j.getDescription()).category(j.getCategory()).priority(j.getPriority()).roomId(j.getRoomId()).roomNumber(j.getRoomNumber())
                    .area(j.getArea() == null && j.getRoomId() == null ? "Scheduled" : j.getArea()).source("SCHEDULED").reportedByName("Preventive schedule")
                    .reportedByRole("SYSTEM").assetId(j.getAssetId()).scheduleId(j.getId()).scheduleDue(due).assignedTo(j.getAssignedTo())
                    .assignedToName(j.getAssignedToName()).status(j.getAssignedTo() == null ? Repair.Status.OPEN : Repair.Status.ASSIGNED)
                    .dueAt(WorkOrderRules.dueAt(j.getPriority(), now)).build();
            try {
                repairs.saveAndFlush(r);
            } catch (DataIntegrityViolationException e) {
                return false;
            }
            opened = true;
            NoticeEvent n = NoticeEvent.builder().kind("REPAIR").title("Preventive job due · " + r.getNumber()).body(where(r) + " · " + r.getTitle())
                    .link("/repairs/" + r.getId()).userIds(r.getAssignedTo() == null ? null : List.of(r.getAssignedTo()))
                    .roles(r.getAssignedTo() == null ? List.of("MAINTENANCE") : null).build();
            n.setBusinessId(j.getBusinessId());
            n.setHotelId(j.getHotelId());
            events.publish(KafkaTopics.NOTICE, n);
        }
        j.setLastOpened(due);
        j.setNextDue(WorkOrderRules.nextDueAfter(due, j.getEveryDays(), today));
        jobs.save(j);
        return opened;
    }

    /** Every 15 minutes: open work orders past their due time tell the manager and the assignee, once each. */
    @Scheduled(cron = "0 */15 * * * *", zone = "Africa/Lagos")
    public void flagOverdue() {
        TenantContext.runWith(Tenant.system("work-order-overdue"), () -> {
            TransactionTemplate tx = new TransactionTemplate(transactionManager);
            tx.executeWithoutResult(s -> {
                for (Repair r : repairs.findByStatusInAndDueAtBeforeAndOverdueNotifiedFalse(WorkOrderRules.OPEN, Instant.now())) {
                    r.setOverdueNotified(true);
                    repairs.save(r);
                    NoticeEvent n = NoticeEvent.builder().kind("REPAIR_OVERDUE").title(r.getNumber() + " is overdue")
                            .body(where(r) + " · " + r.getTitle() + " · " + r.getPriority().name().toLowerCase(Locale.ROOT) + " priority")
                            .link("/repairs/" + r.getId()).userIds(r.getAssignedTo() == null ? null : List.of(r.getAssignedTo())).roles(List.of("MANAGER")).build();
                    n.setBusinessId(r.getBusinessId());
                    n.setHotelId(r.getHotelId());
                    events.publish(KafkaTopics.NOTICE, n);
                }
            });
        });
    }

    // ---- helpers ------------------------------------------------------------------------------------

    private Repair find(String id) {
        return repairs.findById(id).orElseThrow(() -> new ResourceNotFoundException("Work order not found"));
    }

    private ScheduledJob job(String id) {
        return jobs.findById(id).filter(j -> !j.isDeleted()).orElseThrow(() -> new ResourceNotFoundException("Scheduled job not found"));
    }

    private void requireRun(HospioUserDetails u, Repair r) {
        if (!WorkOrderAccess.canRun(u, r)) {
            throw new ForbiddenException("Only the technician on " + r.getNumber() + ", the maintenance supervisor or a manager can do that");
        }
    }

    private static void requireSupervisor() {
        if (!WorkOrderAccess.supervisor(CurrentUser.require())) {
            throw new ForbiddenException("Assets and schedules are for the maintenance supervisor or a manager");
        }
    }

    /** Next WO-YYYY-NNNNN for the hotel, under a transaction-scoped lock so two desks never get the same number. */
    private String nextNumber(String hotelId) {
        int year = LocalDate.now(LAGOS).getYear();
        repairs.lock("wo-number:" + hotelId);
        return WorkOrderRules.number(year, repairs.lastNumber(hotelId, "WO-" + year + "-%") + 1);
    }

    private static Repair.Category category(String value, Repair.Category fallback) {
        if (value == null || value.isBlank()) {
            if (fallback != null) {
                return fallback;
            }
            throw new BadRequestException("Pick a category: " + categories());
        }
        try {
            return Repair.Category.valueOf(value.trim().toUpperCase(Locale.ROOT).replace(' ', '_'));
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("Pick a category: " + categories());
        }
    }

    private static String categories() {
        return Arrays.stream(Repair.Category.values()).map(c -> c.name().toLowerCase(Locale.ROOT).replace('_', ' ')).collect(Collectors.joining(", "));
    }

    private static String reason(MaintenanceRequests.ReasonRequest request, String missing) {
        String r = request == null ? null : blank(request.reason());
        if (r == null) {
            throw new BadRequestException(missing);
        }
        if (r.length() > 300) {
            throw new BadRequestException("Keep the reason under 300 characters");
        }
        return r;
    }

    private void log(Repair r, HospioUserDetails u, String note) {
        updates.save(RepairUpdate.builder().repairId(r.getId()).note(note.length() > 1000 ? note.substring(0, 1000) : note).byUser(u.getUserId())
                .byName(u.getFullName()).build());
    }

    private List<String> raiser(Repair r, HospioUserDetails actor) {
        List<String> l = raiserList(r, actor);
        return l.isEmpty() ? null : l;
    }

    private static List<String> raiserList(Repair r, HospioUserDetails actor) {
        return r.getReportedBy() != null && !r.getReportedBy().equals(actor.getUserId()) ? List.of(r.getReportedBy()) : List.of();
    }

    private void notice(Repair r, String kind, String title, String body, List<String> users, List<String> roles) {
        if ((users == null || users.isEmpty()) && (roles == null || roles.isEmpty())) {
            return;
        }
        events.publish(KafkaTopics.NOTICE, NoticeEvent.builder().kind(kind).title(title).body(body).link("/repairs/" + r.getId()).userIds(users)
                .roles(roles).build());
    }

    private static String where(Repair r) {
        return r.getRoomNumber() != null ? "Room " + r.getRoomNumber() : nz(r.getArea(), "Hotel");
    }

    Map<String, Asset> assetMap(String hotel) {
        return hotel == null ? Map.of() : assets.findByHotelIdOrderByName(hotel).stream().collect(Collectors.toMap(Asset::getId, Function.identity()));
    }

    private List<MaintenanceViews.PartView> parts(Repair r) {
        if (r.getParts() == null || r.getParts().isBlank()) {
            return List.of();
        }
        try {
            return mapper.readValue(r.getParts(), new TypeReference<List<MaintenanceViews.PartView>>() {
            });
        } catch (Exception e) {
            return List.of();
        }
    }

    private String write(Object o) {
        try {
            return mapper.writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    MaintenanceViews.RepairView view(Repair r, Map<String, Asset> am, List<MaintenanceViews.UpdateView> ups, Instant now) {
        Asset a = r.getAssetId() == null ? null : am.get(r.getAssetId());
        BigDecimal parts = z(r.getPartsCost());
        BigDecimal vendor = z(r.getVendorCost());
        return new MaintenanceViews.RepairView(r.getId(), r.getNumber(), r.getRoomId(), r.getRoomNumber(), r.getArea(), r.getCategory().name(),
                r.getPriority().name(), r.getStatus().name(), r.getTitle(), r.getDescription(), r.getSource(), r.getReportedBy(), r.getReportedByName(),
                r.getReportedByRole(), r.getAssignedTo(), r.getAssignedToName(), r.getDueAt(), WorkOrderRules.overdue(r.getStatus(), r.getDueAt(), now),
                r.getStartedAt(), r.getHoldReason(), r.getDoneAt(), r.getCompletionNote(), r.getLabourMinutes(), parts(r), parts, r.getVendorName(), vendor,
                Money.of(parts.add(vendor)), r.isRoomOutOfOrder(), r.getOooUntil(), r.getReopenCount(), r.getReopenReason(), r.getCancelReason(),
                r.getPhotoKey() != null && r.getPhotoKey().startsWith("maintenance/"), r.getAssetId(), a == null ? null : a.getName(), r.getScheduleId(),
                r.getCreatedAt(), ups);
    }

    private static MaintenanceViews.AssetView assetView(Asset a, long open) {
        return new MaintenanceViews.AssetView(a.getId(), a.getName(), a.getCategory().name(), a.getRoomId(), a.getRoomNumber(), a.getArea(), a.isActive(), open);
    }

    private static MaintenanceViews.JobView jobView(ScheduledJob j, Map<String, Asset> am) {
        Asset a = j.getAssetId() == null ? null : am.get(j.getAssetId());
        return new MaintenanceViews.JobView(j.getId(), j.getTitle(), j.getDescription(), j.getCategory().name(), j.getPriority().name(), j.getEveryDays(),
                j.getNextDue(), j.getLastDone(), j.getLastOpened(), j.getAssetId(), a == null ? null : a.getName(), j.getRoomId(), j.getRoomNumber(),
                j.getArea(), j.getAssignedTo(), j.getAssignedToName(), j.isPaused());
    }

    private static BigDecimal z(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static String nz(String s, String fallback) {
        return s == null || s.isBlank() ? fallback : s;
    }
}
