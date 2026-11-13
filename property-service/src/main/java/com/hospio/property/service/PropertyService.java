package com.hospio.property.service;

import com.hospio.common.exception.BadRequestException;
import com.hospio.common.exception.ConflictException;
import com.hospio.common.exception.ExternalServiceException;
import com.hospio.common.exception.ResourceNotFoundException;
import com.hospio.common.kafka.EventPublisher;
import com.hospio.common.kafka.KafkaTopics;
import com.hospio.common.kafka.events.ApprovalRequestedEvent;
import com.hospio.common.util.Money;
import java.util.UUID;
import com.hospio.common.kafka.events.RoomBackInServiceEvent;
import com.hospio.common.kafka.events.RoomOutOfOrderEvent;
import com.hospio.common.tenancy.Tenant;
import com.hospio.common.tenancy.TenantContext;
import com.hospio.property.client.TenantClient;
import com.hospio.property.dto.AreaRequest;
import com.hospio.property.dto.BulkRoomsRequest;
import com.hospio.property.dto.BulkRoomsResponse;
import com.hospio.property.dto.OutOfOrderRequest;
import com.hospio.property.dto.RoomRequest;
import com.hospio.property.dto.RoomResponse;
import com.hospio.property.dto.RoomTypeRequest;
import com.hospio.property.dto.RoomTypeResponse;
import com.hospio.property.dto.SpecialPriceRequest;
import com.hospio.property.entity.Area;
import com.hospio.property.entity.Room;
import com.hospio.property.entity.RoomType;
import com.hospio.property.entity.SpecialPrice;
import com.hospio.property.repository.AreaRepository;
import com.hospio.property.repository.RoomRepository;
import com.hospio.property.repository.RoomTypeRepository;
import com.hospio.property.repository.SpecialPriceRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Room types, rooms and areas. Writes run inside the hotel's business: a platform admin setting up a
 * hotel carries no business of their own, so it is looked up from tenant-service and bound as the system.
 */
@Service
@RequiredArgsConstructor
public class PropertyService {

    private final RoomTypeRepository roomTypes;
    private final RoomRepository rooms;
    private final AreaRepository areas;
    private final SpecialPriceRepository specialPrices;
    private final TenantClient tenantClient;
    private final EventPublisher events;
    private final com.hospio.property.client.HotelConfigClient hotelConfig;
    private final com.hospio.property.client.ReservationClient reservations;

    @Transactional(readOnly = true)
    public List<RoomTypeResponse> roomTypes(String hotelId) {
        return roomTypes.findByHotelIdOrderByName(hotelId).stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public RoomTypeResponse roomType(String id) {
        return toResponse(findType(id));
    }

    @Transactional
    public RoomTypeResponse saveRoomType(String id, RoomTypeRequest request) {
        String hotelId = TenantContext.requireHotel();
        return withHotelTenant(hotelId, () -> {
            RoomType type = id == null ? RoomType.builder().hotelId(hotelId).build() : findType(id);
            type.setPriceSentToOwner(false);
            if (request.basePrice().signum() <= 0) {
                throw new BadRequestException("Enter a base price above zero");
            }
            String typeName = RoomRules.typeName(request.name());
            RoomRules.guests(request.capacityAdults());
            if (request.capacityChildren() != null && (request.capacityChildren() < 0 || request.capacityChildren() > 20)) {
                throw new BadRequestException("Children per room must be between 0 and 20");
            }
            if (id == null || !RoomRules.key(typeName).equals(RoomRules.key(type.getName()))) {
                roomTypes.otherActiveWithName(type.getHotelId(), id == null ? "" : id, RoomRules.key(typeName)).ifPresent(other -> {
                    throw new ConflictException("There is already a room type called " + other + " in this hotel. One type has one price: "
                            + "add the rooms to " + other + ", or give this type a different name");
                });
            }
            BigDecimal price = Money.of(request.basePrice());
            BigDecimal toOwner = null;
            if (id != null && type.getBasePrice() != null && price.compareTo(type.getBasePrice()) != 0) {
                com.hospio.common.security.HospioUserDetails user = com.hospio.common.security.CurrentUser.get().orElse(null);
                boolean ownerSets = user == null || user.isPlatform() || user.has(com.hospio.common.enums.Role.OWNER);
                if (!ownerSets && RoomPriceRule.toOwner(hotelConfig.roomPricePolicy(hotelId), type.getBasePrice(), price)) {
                    // Over the owner's threshold: everything else is saved now, the price waits for the owner (as folio's credit limits).
                    if (type.getPriceApprovalId() != null && type.getRequestedBasePrice() != null) {
                        if (type.getRequestedBasePrice().compareTo(price) != 0) {
                            throw new BadRequestException("A price of " + naira(type.getRequestedBasePrice()) + " for " + type.getName()
                                    + " is already waiting for the owner. Wait for their answer first");
                        }
                    } else {
                        toOwner = price;
                    }
                    price = type.getBasePrice();
                } else if (ownerSets && type.getPriceApprovalId() != null) {
                    // The owner set the price themselves: the waiting request is answered by that.
                    clearPriceRequest(type);
                }
            }
            BigDecimal oldPrice = type.getBasePrice();
            type.setName(typeName);
            type.setDescription(request.description());
            type.setBasePrice(price);
            if (request.capacityAdults() != null) {
                type.setCapacityAdults(request.capacityAdults());
            }
            if (request.capacityChildren() != null) {
                type.setCapacityChildren(request.capacityChildren());
            }
            type.setFacilities(request.facilities());
            if (request.active() != null) {
                type.setActive(request.active());
            }
            RoomType saved = roomTypes.save(type);
            if (toOwner != null) {
                com.hospio.common.security.HospioUserDetails user = com.hospio.common.security.CurrentUser.require();
                String approvalId = UUID.randomUUID().toString();
                saved.setRequestedBasePrice(toOwner);
                saved.setPriceApprovalId(approvalId);
                saved.setPriceRequestedByName(user.getFullName());
                saved.setPriceRequestedAt(java.time.Instant.now());
                saved = roomTypes.save(saved);
                BigDecimal pct = toOwner.subtract(oldPrice).multiply(BigDecimal.valueOf(100)).divide(oldPrice, 1, java.math.RoundingMode.HALF_UP);
                events.publish(KafkaTopics.APPROVAL_REQUESTED, ApprovalRequestedEvent.builder()
                        .approvalId(approvalId).sourceModule(PROPERTY).sourceRef(saved.getId()).kind(ROOM_PRICE).amount(toOwner)
                        .requesterId(user.getUserId()).requesterName(user.getFullName()).approverLevel("OWNER")
                        .description(saved.getName() + " · base price " + naira(oldPrice) + " → " + naira(toOwner) + " ("
                                + (pct.signum() > 0 ? "+" : "") + pct.stripTrailingZeros().toPlainString() + "%)").build());
                saved.setPriceSentToOwner(true);
            }
            return toResponse(saved);
        });
    }

    public static final String PROPERTY = "PROPERTY";
    public static final String ROOM_PRICE = "ROOM_PRICE";

    /**
     * The owner's answer to a room price request (APPROVAL_DECIDED from platform-service): approved, the requested price becomes the
     * base price; declined, the old one stays. A question leaves it waiting. Answers to a request that was replaced or settled are ignored.
     */
    @Transactional
    public void applyPriceDecision(String approvalId, String decision) {
        roomTypes.findByPriceApprovalId(approvalId)
                .filter(t -> approvalId.equals(t.getPriceApprovalId()) && t.getRequestedBasePrice() != null).ifPresent(type -> {
            if ("APPROVED".equals(decision)) {
                type.setBasePrice(Money.of(type.getRequestedBasePrice()));
                clearPriceRequest(type);
            } else if ("DECLINED".equals(decision)) {
                clearPriceRequest(type);
            } else {
                return;
            }
            roomTypes.save(type);
        });
    }

    private static void clearPriceRequest(RoomType type) {
        type.setRequestedBasePrice(null);
        type.setPriceApprovalId(null);
        type.setPriceRequestedByName(null);
        type.setPriceRequestedAt(null);
    }

    static String naira(BigDecimal amount) {
        java.text.NumberFormat f = java.text.NumberFormat.getNumberInstance(java.util.Locale.ENGLISH);
        f.setMaximumFractionDigits(amount != null && amount.stripTrailingZeros().scale() > 0 ? 2 : 0);
        return "₦" + f.format(amount == null ? BigDecimal.ZERO : amount);
    }

    /** Hides a room type nobody uses; one with rooms is refused (409) until they are moved. */
    @Transactional
    public RoomTypeResponse deactivateRoomType(String id) {
        RoomType type = findType(id);
        if (rooms.existsByRoomTypeId(id)) {
            throw new ConflictException("Rooms still use the " + type.getName() + " type. Move them to another type first");
        }
        type.setActive(false);
        return toResponse(roomTypes.save(type));
    }

    @Transactional
    public RoomTypeResponse addSpecialPrice(String roomTypeId, SpecialPriceRequest request) {
        RoomType type = findType(roomTypeId);
        if (request.toDate().isBefore(request.fromDate())) {
            throw new BadRequestException("The end date is before the start date");
        }
        return withHotelTenant(type.getHotelId(), () -> {
            specialPrices.save(SpecialPrice.builder().hotelId(type.getHotelId()).roomTypeId(type.getId()).name(request.name())
                    .price(request.price()).fromDate(request.fromDate()).toDate(request.toDate()).build());
            return toResponse(type);
        });
    }

    /** The nightly price for a type on a date: a special price if one covers it, else the base price. */
    @Transactional(readOnly = true)
    public BigDecimal priceFor(String roomTypeId, LocalDate date) {
        RoomType type = findType(roomTypeId);
        return specialPrices.findByRoomTypeIdOrderByFromDate(roomTypeId).stream().filter(sp -> sp.covers(date))
                .map(SpecialPrice::getPrice).findFirst().orElse(type.getBasePrice());
    }

    @Transactional(readOnly = true)
    public List<RoomResponse> rooms(String hotelId, String roomTypeId) {
        List<Room> list = roomTypeId == null ? rooms.findByHotelIdOrderByNumber(hotelId) : rooms.findByHotelIdAndRoomTypeIdOrderByNumber(hotelId, roomTypeId);
        Map<String, RoomType> types = roomTypes.findByHotelIdOrderByName(hotelId).stream().collect(Collectors.toMap(RoomType::getId, Function.identity()));
        return list.stream().map(r -> toResponse(r, types.get(r.getRoomTypeId()))).toList();
    }

    @Transactional(readOnly = true)
    public RoomResponse room(String id) {
        Room room = findRoom(id);
        return toResponse(room, findType(room.getRoomTypeId()));
    }

    @Transactional
    public RoomResponse saveRoom(String id, RoomRequest request) {
        String hotelId = TenantContext.requireHotel();
        return withHotelTenant(hotelId, () -> {
            RoomType type = findType(request.roomTypeId());
            String number = RoomRules.number(request.number());
            if (!hotelId.equals(type.getHotelId())) {
                throw new BadRequestException("Room type " + type.getName() + " belongs to another hotel");
            }
            rooms.findByHotelIdAndNumber(hotelId, number).filter(r -> !r.getId().equals(id)).ifPresent(r -> {
                throw new ConflictException("Room " + number + " already exists");
            });
            Room room = id == null ? Room.builder().hotelId(hotelId).build() : findRoom(id);
            room.setNumber(number);
            room.setRoomTypeId(type.getId());
            room.setAreaId(request.areaId());
            room.setFloor(RoomRules.floor(request.floor()));
            room.setNotes(request.notes());
            Room saved = rooms.save(room);
            if (id == null) {
                tenantClient.updateRoomCount(hotelId, rooms.countByHotelId(hotelId));
            }
            return toResponse(saved, type);
        });
    }

    /**
     * Onboarding: many rooms at once. A number already in the hotel, or repeated in the request, is
     * skipped and reported; every room type must belong to this hotel. The room count is synced after.
     */
    @Transactional
    public BulkRoomsResponse bulkRooms(BulkRoomsRequest request) {
        String hotelId = TenantContext.requireHotel();
        return withHotelTenant(hotelId, () -> {
            Set<String> taken = new HashSet<>();
            rooms.findByHotelIdOrderByNumber(hotelId).forEach(r -> taken.add(r.getNumber().trim().toUpperCase()));
            Map<String, RoomType> types = new HashMap<>();
            List<String> skipped = new ArrayList<>();
            List<String> existing = new ArrayList<>();
            List<String> repeated = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            List<Room> toSave = new ArrayList<>();
            for (BulkRoomsRequest.BulkRoom r : request.rooms()) {
                String number = RoomRules.number(r.number());
                String floor = RoomRules.floor(r.floor());
                RoomType type = types.computeIfAbsent(r.roomTypeId(), this::findType);
                if (!hotelId.equals(type.getHotelId())) {
                    throw new BadRequestException("Room type " + type.getName() + " belongs to another hotel");
                }
                String key = number.toUpperCase();
                if (!seen.add(key)) {
                    repeated.add(number);
                    skipped.add(number);
                    continue;
                }
                if (!taken.add(key)) {
                    existing.add(number);
                    skipped.add(number);
                    continue;
                }
                toSave.add(Room.builder().hotelId(hotelId).roomTypeId(type.getId()).areaId(r.areaId()).number(number).floor(floor).build());
            }
            if (Boolean.TRUE.equals(request.refuseExisting()) && !skipped.isEmpty()) {
                StringBuilder msg = new StringBuilder("No rooms were made. ");
                if (!existing.isEmpty()) {
                    msg.append(existing.size() == 1 ? "Room " : "Rooms ").append(RoomRules.ranges(existing))
                            .append(existing.size() == 1 ? " already exists" : " already exist").append(" in this hotel. ");
                }
                if (!repeated.isEmpty()) {
                    msg.append(repeated.size() == 1 ? "Room " : "Rooms ").append(RoomRules.ranges(repeated)).append(" appear more than once in the list. ");
                }
                throw new ConflictException(msg.append("Choose numbers that are free").toString());
            }
            rooms.saveAll(toSave);
            int created = toSave.size();
            if (created > 0) {
                tenantClient.updateRoomCount(hotelId, rooms.countByHotelId(hotelId));
            }
            return new BulkRoomsResponse(created, skipped);
        });
    }

    /**
     * Deletes a room made by mistake (onboarding, Manager · Rooms). A room that has ever had a booking is refused: its history
     * stays, and it can be taken out of order instead. The hotel's room count is synced after.
     */
    @Transactional
    public RoomResponse deleteRoom(String id) {
        Room room = findRoom(id);
        String hotelId = room.getHotelId();
        RoomType type = roomTypes.findById(room.getRoomTypeId()).orElse(null);
        long booked = reservations.bookingCount(id);
        if (booked > 0) {
            throw new ConflictException("Room " + room.getNumber() + " has " + (booked == 1 ? "a booking" : booked + " bookings")
                    + ", so it can't be deleted. Take it out of order instead");
        }
        return withHotelTenant(hotelId, () -> {
            rooms.delete(room);
            rooms.flush();
            tenantClient.updateRoomCount(hotelId, rooms.countByHotelId(hotelId));
            return toResponse(room, type);
        });
    }

    @Transactional
    public RoomResponse outOfOrder(String id, OutOfOrderRequest request) {
        Room room = findRoom(id);
        room.setStatus(Room.Status.OUT_OF_ORDER);
        room.setOutOfOrderReason(request.reason());
        room.setOutOfOrderUntil(request.until());
        rooms.save(room);
        events.publish(KafkaTopics.ROOM_OUT_OF_ORDER, RoomOutOfOrderEvent.builder()
                .hotelId(room.getHotelId()).roomId(room.getId()).roomNumber(room.getNumber()).reason(request.reason()).until(request.until()).build());
        return toResponse(room, findType(room.getRoomTypeId()));
    }

    @Transactional
    public RoomResponse backInService(String id) {
        Room room = findRoom(id);
        room.setStatus(Room.Status.IN_SERVICE);
        room.setOutOfOrderReason(null);
        room.setOutOfOrderUntil(null);
        rooms.save(room);
        events.publish(KafkaTopics.ROOM_BACK_IN_SERVICE, RoomBackInServiceEvent.builder()
                .hotelId(room.getHotelId()).roomId(room.getId()).roomNumber(room.getNumber()).build());
        return toResponse(room, findType(room.getRoomTypeId()));
    }

    @Transactional(readOnly = true)
    public List<Area> areas(String hotelId) {
        return areas.findByHotelIdOrderBySortOrderAscNameAsc(hotelId);
    }

    @Transactional
    public Area saveArea(String id, AreaRequest request) {
        String hotelId = TenantContext.requireHotel();
        return withHotelTenant(hotelId, () -> {
            Area area = id == null ? Area.builder().hotelId(hotelId).build()
                    : areas.findById(id).orElseThrow(() -> new ResourceNotFoundException("Area not found"));
            // A floor or wing as people write it ("Floor 2", "Block A · Floor 1", "Annex"): 1-60 characters with a letter
            // or digit in it, and not the name of another area in this hotel.
            String name = request.name() == null ? "" : request.name().trim().replaceAll("\\s+", " ");
            if (name.isEmpty() || name.length() > 60 || !name.matches(".*[\\p{L}\\d].*")) {
                throw new BadRequestException("Name the floor or wing, like Floor 2 or Block A");
            }
            areas.findByHotelIdOrderBySortOrderAscNameAsc(hotelId).stream()
                    .filter(a -> !a.getId().equals(area.getId()) && a.getName().equalsIgnoreCase(name)).findFirst()
                    .ifPresent(a -> {
                        throw new ConflictException(a.getName() + " is already a floor or wing in this hotel");
                    });
            if (id == null && request.sortOrder() == null) {
                // New ones go after the ones already there.
                area.setSortOrder(areas.findByHotelIdOrderBySortOrderAscNameAsc(hotelId).size() + 1);
            }
            area.setName(name);
            if (request.kind() != null) {
                area.setKind(request.kind());
            }
            if (request.sortOrder() != null) {
                area.setSortOrder(request.sortOrder());
            }
            return areas.save(area);
        });
    }

    /**
     * Business and hotel callers run as themselves. A platform caller has no business, and every row
     * here needs one, so the hotel's business is looked up and the work runs as the system inside it.
     */
    <T> T withHotelTenant(String hotelId, Supplier<T> work) {
        Tenant current = TenantContext.current().orElse(null);
        if (current != null && current.businessId() != null) {
            return work.get();
        }
        String businessId = tenantClient.businessOf(hotelId)
                .orElseThrow(() -> new ExternalServiceException("Could not find which business hotel " + hotelId + " belongs to"));
        Tenant bound = Tenant.systemFor(businessId, hotelId, current == null ? null : current.actorId(), current == null ? null : current.actorName());
        return TenantContext.runWith(bound, work);
    }

    private RoomType findType(String id) {
        return roomTypes.findById(id).orElseThrow(() -> new ResourceNotFoundException("Room type not found"));
    }

    private Room findRoom(String id) {
        return rooms.findById(id).orElseThrow(() -> new ResourceNotFoundException("Room not found"));
    }

    private RoomTypeResponse toResponse(RoomType t) {
        return new RoomTypeResponse(t.getId(), t.getHotelId(), t.getName(), t.getDescription(), t.getBasePrice(), t.getCapacityAdults(),
                t.getCapacityChildren(), t.getFacilities(), t.isActive(),
                rooms.findByHotelIdAndRoomTypeIdOrderByNumber(t.getHotelId(), t.getId()).size(),
                specialPrices.findByRoomTypeIdOrderByFromDate(t.getId()), t.getRequestedBasePrice(), t.getPriceRequestedByName(),
                t.getPriceRequestedAt(), t.isPriceSentToOwner());
    }

    private static RoomResponse toResponse(Room r, RoomType t) {
        return new RoomResponse(r.getId(), r.getHotelId(), r.getNumber(), r.getFloor(), r.getAreaId(), r.getRoomTypeId(),
                t == null ? null : t.getName(), t == null ? null : t.getBasePrice(), t == null ? 0 : t.getCapacityAdults(),
                t == null ? 0 : t.getCapacityChildren(), r.getStatus(), r.getOutOfOrderReason(), r.getOutOfOrderUntil(), r.getNotes());
    }
}
