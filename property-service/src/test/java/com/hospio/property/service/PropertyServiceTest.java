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
    private final com.hospio.property.client.HotelConfigClient hotelConfig = mock(com.hospio.property.client.HotelConfigClient.class);
    private final PropertyService service = new PropertyService(roomTypes, rooms, areas, specialPrices, tenant, events, hotelConfig);

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
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
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

    // ---- room price changes waiting for the owner -----------------------------------------------------

    private static void signInAs(com.hospio.common.enums.Role role) {
        com.hospio.common.security.HospioUserDetails u = new com.hospio.common.security.HospioUserDetails("u-" + role, "x@x.ng",
                role == com.hospio.common.enums.Role.OWNER ? "Godwin Kwin" : "Tunde Bakare", "b1", List.of("h1"),
                java.util.Map.of(role, com.hospio.common.enums.AccessLevel.FULL),
                role == com.hospio.common.enums.Role.OWNER ? com.hospio.common.security.JwtUtil.SCOPE_BUSINESS : com.hospio.common.security.JwtUtil.SCOPE_HOTEL);
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
                new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(u, null, u.getAuthorities()));
    }

    private com.hospio.property.dto.RoomTypeResponse price(String amount) {
        when(roomTypes.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(rooms.findByHotelIdAndRoomTypeIdOrderByNumber("h1", "t1")).thenReturn(List.of());
        return service.saveRoomType("t1", new com.hospio.property.dto.RoomTypeRequest("Deluxe", "Renamed", new BigDecimal(amount), 2, 1, "AC", true));
    }

    private void policy(boolean needsOwner, String pct) {
        when(hotelConfig.roomPricePolicy("h1")).thenReturn(new RoomPriceRule.Policy(needsOwner, new BigDecimal(pct)));
    }

    @Test
    void thresholdZeroMeansEveryChangeAndTenMeansAboveTenPercent() {
        RoomPriceRule.Policy any = new RoomPriceRule.Policy(true, BigDecimal.ZERO);
        RoomPriceRule.Policy ten = new RoomPriceRule.Policy(true, BigDecimal.TEN);
        assertThat(RoomPriceRule.toOwner(any, new BigDecimal("45000"), new BigDecimal("45001"))).isTrue();
        assertThat(RoomPriceRule.toOwner(any, new BigDecimal("45000"), new BigDecimal("45000"))).isFalse();
        assertThat(RoomPriceRule.toOwner(ten, new BigDecimal("10000"), new BigDecimal("11000"))).isFalse();
        assertThat(RoomPriceRule.toOwner(ten, new BigDecimal("10000"), new BigDecimal("11001"))).isTrue();
        assertThat(RoomPriceRule.toOwner(ten, new BigDecimal("10000"), new BigDecimal("8999"))).isTrue();
        assertThat(RoomPriceRule.toOwner(new RoomPriceRule.Policy(false, BigDecimal.ZERO), new BigDecimal("10000"), new BigDecimal("90000"))).isFalse();
    }

    @Test
    void aManagersChangeOverTheThresholdSavesTheRestAndWaitsForTheOwner() {
        signInAs(com.hospio.common.enums.Role.MANAGER);
        policy(true, "10");
        var r = price("55000");
        assertThat(r.basePrice()).isEqualByComparingTo("45000");
        assertThat(r.requestedBasePrice()).isEqualByComparingTo("55000");
        assertThat(r.priceSentToOwner()).isTrue();
        assertThat(r.description()).isEqualTo("Renamed");
        verify(events).publish(eq(com.hospio.common.kafka.KafkaTopics.APPROVAL_REQUESTED),
                org.mockito.ArgumentMatchers.argThat((com.hospio.common.kafka.events.ApprovalRequestedEvent e) -> "PROPERTY".equals(e.getSourceModule())
                        && "ROOM_PRICE".equals(e.getKind()) && "OWNER".equals(e.getApproverLevel())
                        && e.getDescription().equals("Deluxe · base price ₦45,000 → ₦55,000 (+22.2%)")));
    }

    @Test
    void aChangeWithinTheThresholdAppliesAtOnce() {
        signInAs(com.hospio.common.enums.Role.MANAGER);
        policy(true, "10");
        var r = price("49500");
        assertThat(r.basePrice()).isEqualByComparingTo("49500");
        assertThat(r.requestedBasePrice()).isNull();
        verify(events, never()).publish(any(), any());
    }

    @Test
    void aDifferentSecondRequestIsRefusedAndTheSameOneIsANoOp() {
        signInAs(com.hospio.common.enums.Role.MANAGER);
        policy(true, "0");
        price("50000");
        assertThatThrownBy(() -> price("60000")).isInstanceOf(com.hospio.common.exception.BadRequestException.class)
                .hasMessage("A price of ₦50,000 for Deluxe is already waiting for the owner. Wait for their answer first");
        var again = price("50000");
        assertThat(again.priceSentToOwner()).isFalse();
        assertThat(again.requestedBasePrice()).isEqualByComparingTo("50000");
        verify(events, times(1)).publish(any(), any());
    }

    @Test
    void theOwnerSetsThePriceDirectlyAndSettlesAWaitingRequest() {
        signInAs(com.hospio.common.enums.Role.MANAGER);
        policy(true, "0");
        price("50000");
        signInAs(com.hospio.common.enums.Role.OWNER);
        var r = price("52000");
        assertThat(r.basePrice()).isEqualByComparingTo("52000");
        assertThat(r.requestedBasePrice()).isNull();
        assertThat(deluxe.getPriceApprovalId()).isNull();
    }

    @Test
    void approvedAppliesTheRequestedPriceDeclinedKeepsTheOldAQuestionWaits() {
        signInAs(com.hospio.common.enums.Role.MANAGER);
        policy(true, "0");
        price("50000");
        String approval = deluxe.getPriceApprovalId();
        when(roomTypes.findByPriceApprovalId(approval)).thenReturn(Optional.of(deluxe));

        service.applyPriceDecision(approval, "QUESTION");
        assertThat(deluxe.getBasePrice()).isEqualByComparingTo("45000");
        assertThat(deluxe.getRequestedBasePrice()).isEqualByComparingTo("50000");

        service.applyPriceDecision(approval, "APPROVED");
        assertThat(deluxe.getBasePrice()).isEqualByComparingTo("50000");
        assertThat(deluxe.getPriceApprovalId()).isNull();

        price("40000");
        String second = deluxe.getPriceApprovalId();
        when(roomTypes.findByPriceApprovalId(second)).thenReturn(Optional.of(deluxe));
        service.applyPriceDecision(second, "DECLINED");
        assertThat(deluxe.getBasePrice()).isEqualByComparingTo("50000");
        assertThat(deluxe.getRequestedBasePrice()).isNull();
        service.applyPriceDecision(second, "APPROVED");
        assertThat(deluxe.getBasePrice()).isEqualByComparingTo("50000");
    }

    @Test
    void hotelConfigUnreachableMeansTheOwnerDecides() {
        signInAs(com.hospio.common.enums.Role.MANAGER);
        when(hotelConfig.roomPricePolicy("h1")).thenReturn(new RoomPriceRule.Policy(true, BigDecimal.ZERO));
        assertThat(price("46000").priceSentToOwner()).isTrue();
    }
}
