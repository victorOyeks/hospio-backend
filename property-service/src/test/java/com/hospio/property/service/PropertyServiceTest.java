package com.hospio.property.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hospio.common.exception.ConflictException;
import com.hospio.common.kafka.EventPublisher;
import com.hospio.common.tenancy.Tenant;
import com.hospio.common.tenancy.TenantContext;
import com.hospio.property.client.TenantClient;
import com.hospio.property.dto.BulkRoomsRequest;
import com.hospio.property.dto.BulkRoomsResponse;
import com.hospio.property.entity.Room;
import com.hospio.property.entity.RoomType;
import com.hospio.property.repository.AreaRepository;
import com.hospio.property.repository.RoomRepository;
import com.hospio.property.repository.RoomTypeRepository;
import com.hospio.property.repository.SpecialPriceRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Bulk rooms skip numbers already present or repeated, sync the count once; a room type in use cannot be hidden. */
class PropertyServiceTest {

    private final RoomTypeRepository roomTypes = mock(RoomTypeRepository.class);
    private final RoomRepository rooms = mock(RoomRepository.class);
    private final AreaRepository areas = mock(AreaRepository.class);
    private final SpecialPriceRepository specialPrices = mock(SpecialPriceRepository.class);
    private final TenantClient tenant = mock(TenantClient.class);
    private final EventPublisher events = mock(EventPublisher.class);
    private final PropertyService service = new PropertyService(roomTypes, rooms, areas, specialPrices, tenant, events);

    private final RoomType deluxe = RoomType.builder().hotelId("h1").name("Deluxe").basePrice(new BigDecimal("45000")).build();

    @BeforeEach
    void bindHotel() {
        deluxe.setId("t1");
        TenantContext.set(Tenant.systemFor("b1", "h1", "tunde", "Tunde"));
        when(roomTypes.findById("t1")).thenReturn(Optional.of(deluxe));
        when(rooms.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    void bulkSkipsExistingAndRepeatedNumbersAndSyncsTheCountOnce() {
        Room existing = Room.builder().hotelId("h1").roomTypeId("t1").number("A101").build();
        when(rooms.findByHotelIdOrderByNumber("h1")).thenReturn(List.of(existing));
        when(rooms.countByHotelId("h1")).thenReturn(3L);

        BulkRoomsResponse result = service.bulkRooms(new BulkRoomsRequest(List.of(
                new BulkRoomsRequest.BulkRoom("A101", "t1", "1", null),
                new BulkRoomsRequest.BulkRoom("A102", "t1", "1", null),
                new BulkRoomsRequest.BulkRoom(" a102 ", "t1", "1", null),
                new BulkRoomsRequest.BulkRoom("A103", "t1", "1", null))));

        assertThat(result.created()).isEqualTo(2);
        assertThat(result.skipped()).containsExactly("A101", "a102");
        verify(rooms, times(2)).save(any());
        verify(tenant).updateRoomCount("h1", 3L);
    }

    @Test
    void bulkWithNothingNewDoesNotTouchTenant() {
        when(rooms.findByHotelIdOrderByNumber("h1")).thenReturn(List.of(Room.builder().hotelId("h1").roomTypeId("t1").number("A101").build()));

        BulkRoomsResponse result = service.bulkRooms(new BulkRoomsRequest(List.of(new BulkRoomsRequest.BulkRoom("A101", "t1", null, null))));

        assertThat(result.created()).isZero();
        verify(tenant, never()).updateRoomCount(eq("h1"), any(Long.class));
    }

    @Test
    void roomTypeInUseCannotBeHidden() {
        when(rooms.existsByRoomTypeId("t1")).thenReturn(true);
        assertThatThrownBy(() -> service.deactivateRoomType("t1")).isInstanceOf(ConflictException.class);

        when(rooms.existsByRoomTypeId("t1")).thenReturn(false);
        when(roomTypes.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(rooms.findByHotelIdAndRoomTypeIdOrderByNumber("h1", "t1")).thenReturn(List.of());
        assertThat(service.deactivateRoomType("t1").active()).isFalse();
    }
}
