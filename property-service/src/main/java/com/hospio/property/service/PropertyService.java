package com.hospio.property.service;

import com.hospio.common.exception.BadRequestException;
import com.hospio.common.exception.ConflictException;
import com.hospio.common.exception.ResourceNotFoundException;
import com.hospio.common.kafka.EventPublisher;
import com.hospio.common.kafka.KafkaTopics;
import com.hospio.common.kafka.events.RoomBackInServiceEvent;
import com.hospio.common.kafka.events.RoomOutOfOrderEvent;
import com.hospio.common.tenancy.TenantContext;
import com.hospio.property.client.TenantClient;
import com.hospio.property.dto.AreaRequest;
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
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
        RoomType type = id == null ? new RoomType() : findType(id);
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
    }

    @Transactional
    public RoomTypeResponse addSpecialPrice(String roomTypeId, SpecialPriceRequest request) {
        RoomType type = findType(roomTypeId);
        if (request.toDate().isBefore(request.fromDate())) {
            throw new BadRequestException("The end date is before the start date");
        }
        specialPrices.save(SpecialPrice.builder().roomTypeId(type.getId()).name(request.name()).price(request.price())
                .fromDate(request.fromDate()).toDate(request.toDate()).build());
        return toResponse(type);
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
        RoomType type = findType(request.roomTypeId());
        rooms.findByHotelIdAndNumber(hotelId, request.number()).filter(r -> !r.getId().equals(id)).ifPresent(r -> {
            throw new ConflictException("Room " + request.number() + " already exists");
        });
        Room room = id == null ? new Room() : findRoom(id);
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
        Area area = id == null ? new Area() : areas.findById(id).orElseThrow(() -> new ResourceNotFoundException("Area not found"));
        area.setName(request.name());
        if (request.kind() != null) {
            area.setKind(request.kind());
        }
        if (request.sortOrder() != null) {
            area.setSortOrder(request.sortOrder());
        }
        return areas.save(area);
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
