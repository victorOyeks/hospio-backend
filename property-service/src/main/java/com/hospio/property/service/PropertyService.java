package com.hospio.property.service;

import com.hospio.common.exception.BadRequestException;
import com.hospio.common.exception.ConflictException;
import com.hospio.common.exception.ExternalServiceException;
import com.hospio.common.exception.ResourceNotFoundException;
import com.hospio.common.kafka.EventPublisher;
import com.hospio.common.kafka.KafkaTopics;
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
            type.setName(request.name());
            type.setDescription(request.description());
            type.setBasePrice(request.basePrice());
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
            return toResponse(roomTypes.save(type));
        });
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
            rooms.findByHotelIdAndNumber(hotelId, request.number()).filter(r -> !r.getId().equals(id)).ifPresent(r -> {
                throw new ConflictException("Room " + request.number() + " already exists");
            });
            Room room = id == null ? Room.builder().hotelId(hotelId).build() : findRoom(id);
            room.setNumber(request.number());
            room.setRoomTypeId(type.getId());
            room.setAreaId(request.areaId());
            room.setFloor(request.floor());
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
            int created = 0;
            for (BulkRoomsRequest.BulkRoom r : request.rooms()) {
                String number = r.number().trim();
                RoomType type = types.computeIfAbsent(r.roomTypeId(), this::findType);
                if (!hotelId.equals(type.getHotelId())) {
                    throw new BadRequestException("Room type " + type.getName() + " belongs to another hotel");
                }
                if (!taken.add(number.toUpperCase())) {
                    skipped.add(number);
                    continue;
                }
                rooms.save(Room.builder().hotelId(hotelId).roomTypeId(type.getId()).areaId(r.areaId()).number(number).floor(r.floor()).build());
                created++;
            }
            if (created > 0) {
                tenantClient.updateRoomCount(hotelId, rooms.countByHotelId(hotelId));
            }
            return new BulkRoomsResponse(created, skipped);
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
            area.setName(request.name());
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
                specialPrices.findByRoomTypeIdOrderByFromDate(t.getId()));
    }

    private static RoomResponse toResponse(Room r, RoomType t) {
        return new RoomResponse(r.getId(), r.getHotelId(), r.getNumber(), r.getFloor(), r.getAreaId(), r.getRoomTypeId(),
                t == null ? null : t.getName(), t == null ? null : t.getBasePrice(), t == null ? 0 : t.getCapacityAdults(),
                t == null ? 0 : t.getCapacityChildren(), r.getStatus(), r.getOutOfOrderReason(), r.getOutOfOrderUntil(), r.getNotes());
    }
}
