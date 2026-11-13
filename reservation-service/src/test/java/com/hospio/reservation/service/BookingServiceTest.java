package com.hospio.reservation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.hospio.common.enums.AccessLevel;
import com.hospio.common.enums.BookingStatus;
import com.hospio.common.enums.PaymentPlan;
import com.hospio.common.enums.PriceKind;
import com.hospio.common.enums.Role;
import com.hospio.common.exception.ConflictException;
import com.hospio.common.exception.ForbiddenException;
import com.hospio.common.kafka.EventPublisher;
import com.hospio.common.security.HospioUserDetails;
import com.hospio.common.security.JwtUtil;
import com.hospio.common.tenancy.Tenant;
import com.hospio.common.tenancy.TenantContext;
import com.hospio.reservation.client.PropertyClient;
import com.hospio.reservation.dto.BookingResponse;
import com.hospio.reservation.dto.CreateBookingRequest;
import com.hospio.reservation.dto.GuestRequest;
import com.hospio.reservation.entity.Guest;
import com.hospio.reservation.repository.BookingRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/** A full room type refuses a booking; a receptionist can't give a special price; the total is nights × rate. */
class BookingServiceTest {

    private final BookingRepository bookings = mock(BookingRepository.class);
    private final GuestService guests = mock(GuestService.class);
    private final PropertyClient property = mock(PropertyClient.class);
    private final EventPublisher events = mock(EventPublisher.class);
    private final com.hospio.reservation.client.RoomReadiness readiness = mock(com.hospio.reservation.client.RoomReadiness.class);
    private final BookingService service = new BookingService(bookings, guests, property, events, readiness,
            mock(org.springframework.transaction.PlatformTransactionManager.class));

    private final LocalDate in = LocalDate.now().plusDays(1);
    private final LocalDate out = in.plusDays(3);

    @BeforeEach
    void setUp() {
        when(bookings.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(bookings.findByReference(anyString())).thenReturn(Optional.empty());
        // The row lock reads the same booking the plain read does (each test stubs findById).
        when(bookings.lockById(anyString())).thenAnswer(inv -> bookings.findById(inv.getArgument(0)));
        when(property.rooms("h1", "t1")).thenReturn(List.of(
                new PropertyClient.RoomSummary("r1", "101", "t1", "Standard", true),
                new PropertyClient.RoomSummary("r2", "102", "t1", "Standard", true)));
        when(property.price(eq("t1"), any())).thenReturn(new BigDecimal("45000"));
        Guest guest = Guest.builder().fullName("Ifeoma Nwachukwu").phone("+2348011112222").build();
        guest.setId("g1");
        when(guests.findOrCreate(any())).thenReturn(guest);
        signInAs(Role.FRONT_OFFICE, AccessLevel.EDIT);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
    }

    @Test
    void totalIsNightsTimesRate() {

        BookingResponse b = service.create(request(PriceKind.NORMAL, null));

        assertThat(b.nights()).isEqualTo(3);
        assertThat(b.totalAmount()).isEqualByComparingTo("135000.00");
        assertThat(b.amountDueNow()).isEqualByComparingTo("67500.00");
        assertThat(b.status()).isEqualTo(BookingStatus.BOOKED);
        assertThat(b.reference()).startsWith("HB").hasSize(8);
    }

    @Test
    void fullRoomTypeRefusesTheBooking() {
        when(bookings.activeOnType(eq("h1"), eq("t1"), any(), any())).thenReturn(List.of(stay("x1", "r1", in, out, BookingStatus.BOOKED),
                stay("x2", null, in.minusDays(1), in.plusDays(1), BookingStatus.BOOKED)));

        assertThatThrownBy(() -> service.create(request(PriceKind.NORMAL, null)))
                .isInstanceOf(ConflictException.class).hasMessageContaining("No Standard room is free");
    }

    @Test
    void receptionistCannotGiveASpecialPrice() {
        assertThatThrownBy(() -> service.create(request(PriceKind.SPECIAL, new BigDecimal("30000"))))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void managerCanGiveASpecialPrice() {
        signInAs(Role.MANAGER, AccessLevel.FULL);

        BookingResponse b = service.create(request(PriceKind.SPECIAL, new BigDecimal("30000")));

        assertThat(b.nightlyRate()).isEqualByComparingTo("30000.00");
        assertThat(b.totalAmount()).isEqualByComparingTo("90000.00");
    }

    private com.hospio.reservation.entity.Booking arrivalIn(String roomId) {
        com.hospio.reservation.entity.Booking b = com.hospio.reservation.entity.Booking.builder().reference("HBTEST01").guestId("g1").guestName("Ifeoma")
                .roomTypeId("t1").roomId(roomId).roomNumber("101").checkInDate(LocalDate.now()).checkOutDate(LocalDate.now().plusDays(2))
                .nightlyRate(new BigDecimal("45000")).totalAmount(new BigDecimal("90000")).paymentPlan(PaymentPlan.PAY_AT_CHECKOUT).build();
        b.setId("b1");
        b.setHotelId("h1");
        when(bookings.findById("b1")).thenReturn(Optional.of(b));
        Guest guest = Guest.builder().fullName("Ifeoma").build();
        guest.setId("g1");
        when(guests.get("g1")).thenReturn(guest);
        if (roomId != null) {
            when(property.room(roomId)).thenReturn(new PropertyClient.RoomSummary(roomId, "101", "t1", "Standard", true));
        }
        return b;
    }

    @Test
    void aBookedRoomThatWentOutOfOrderIsRefusedAtCheckIn() {
        arrivalIn("r1");
        when(property.room("r1")).thenReturn(new PropertyClient.RoomSummary("r1", "101", "t1", "Standard", false));

        assertThatThrownBy(() -> service.checkIn("b1", new com.hospio.reservation.dto.CheckInRequest(null, null, null, null, null, null, null)))
                .isInstanceOf(com.hospio.common.exception.BadRequestException.class).hasMessage("Room 101 is out of order · choose another room");
    }

    @Test
    void checkingIntoADirtyRoomWarnsWhenInspectionIsOff() {
        arrivalIn("r1");
        when(readiness.isDirty("h1", "r1")).thenReturn(true);
        when(readiness.inspectDepartures("h1")).thenReturn(false);

        BookingResponse r = service.checkIn("b1", new com.hospio.reservation.dto.CheckInRequest(null, null, null, null, null, null, null));

        assertThat(r.status()).isEqualTo(BookingStatus.CHECKED_IN);
        assertThat(r.warning()).isEqualTo("Room not cleaned yet");
    }

    @Test
    void withInspectionOnADirtyRoomNeedsAManagersOverride() {
        arrivalIn("r1");
        when(readiness.isDirty("h1", "r1")).thenReturn(true);
        when(readiness.inspectDepartures("h1")).thenReturn(true);

        assertThatThrownBy(() -> service.checkIn("b1", new com.hospio.reservation.dto.CheckInRequest(null, null, null, null, null, null, null)))
                .isInstanceOf(com.hospio.common.exception.BadRequestException.class);
        assertThatThrownBy(() -> service.checkIn("b1", new com.hospio.reservation.dto.CheckInRequest(null, null, null, null, null, null, true)))
                .isInstanceOf(ForbiddenException.class);

        signInAs(Role.MANAGER, AccessLevel.FULL);
        BookingResponse r = service.checkIn("b1", new com.hospio.reservation.dto.CheckInRequest(null, null, null, null, null, null, true));
        assertThat(r.warning()).isEqualTo("Room not cleaned yet");
    }

    @Test
    void aCleanRoomChecksInWithNoWarning() {
        arrivalIn("r1");
        when(readiness.isDirty("h1", "r1")).thenReturn(false);

        assertThat(service.checkIn("b1", new com.hospio.reservation.dto.CheckInRequest(null, null, null, null, null, null, null)).warning()).isNull();
    }

    // ---- one guest per room until checked out (docs/occupancy.md) ----------------------------------------------------------

    private final LocalDate today = Occupancy.today();

    private com.hospio.reservation.entity.Booking stay(String id, String roomId, LocalDate from, LocalDate to, BookingStatus status) {
        com.hospio.reservation.entity.Booking b = com.hospio.reservation.entity.Booking.builder().reference("HB" + id).guestId("g-" + id)
                .guestName("Guest " + id).roomTypeId("t1").roomId(roomId).roomNumber(roomId == null ? null : "10" + roomId.substring(1))
                .checkInDate(from).checkOutDate(to).status(status).nightlyRate(new BigDecimal("45000")).totalAmount(new BigDecimal("45000")).build();
        b.setId(id);
        b.setHotelId("h1");
        return b;
    }

    private void inRoom(com.hospio.reservation.entity.Booking occupant) {
        when(bookings.findByRoomIdAndStatus(occupant.getRoomId(), BookingStatus.CHECKED_IN)).thenReturn(List.of(occupant));
        when(bookings.activeOnRoom(eq(occupant.getRoomId()), any(), any())).thenReturn(List.of(occupant));
    }

    private static com.hospio.reservation.dto.CheckInRequest checkInTo(String roomId) {
        return new com.hospio.reservation.dto.CheckInRequest(roomId, null, null, null, null, null, null);
    }

    @Test
    void aGuestDueOutTodayStillHoldsTheRoomAgainstCheckIn() {
        arrivalIn("r1");
        inRoom(stay("dep", "r1", today.minusDays(1), today, BookingStatus.CHECKED_IN));

        assertThatThrownBy(() -> service.checkIn("b1", checkInTo(null)))
                .isInstanceOf(ConflictException.class)
                .hasMessage("Room 101 still has a guest checked in (Guest dep). Check them out first, or choose another room.");
    }

    @Test
    void anOverstayStillHoldsTheRoom() {
        arrivalIn(null);
        when(property.room("r2")).thenReturn(new PropertyClient.RoomSummary("r2", "102", "t1", "Standard", true));
        inRoom(stay("over", "r2", today.minusDays(3), today.minusDays(1), BookingStatus.CHECKED_IN));

        assertThatThrownBy(() -> service.checkIn("b1", checkInTo("r2")))
                .isInstanceOf(ConflictException.class).hasMessageContaining("Room 102 still has a guest checked in (Guest over)");
    }

    @Test
    void onceTheGuestBeforeIsCheckedOutTheArrivalChecksIn() {
        arrivalIn("r1");
        when(bookings.findByRoomIdAndStatus("r1", BookingStatus.CHECKED_IN)).thenReturn(List.of());
        when(bookings.activeOnRoom(eq("r1"), any(), any())).thenReturn(List.of());

        assertThat(service.checkIn("b1", checkInTo(null)).status()).isEqualTo(BookingStatus.CHECKED_IN);
        org.mockito.Mockito.verify(bookings).lock("room:r1");
        org.mockito.Mockito.verify(bookings).saveAndFlush(any());
    }

    @Test
    void twoDesksAtOnceTheDatabaseRefusesTheSecond() {
        arrivalIn("r1");
        when(bookings.saveAndFlush(any())).thenThrow(new org.springframework.dao.DataIntegrityViolationException("ux_bookings_room_in_house"));

        assertThatThrownBy(() -> service.checkIn("b1", checkInTo(null)))
                .isInstanceOf(ConflictException.class).hasMessageContaining("Room 101 already has a guest checked in");
    }

    @Test
    void anArrivalTodayCanBeGivenTheRoomOfAGuestDueOutToday() {
        inRoom(stay("dep", "r1", today.minusDays(2), today, BookingStatus.CHECKED_IN));
        CreateBookingRequest r = new CreateBookingRequest(null, new GuestRequest("Ifeoma Nwachukwu", "+2348011112222", null, null, null, null, null, null),
                "t1", "r1", today, today.plusDays(2), 1, 0, PriceKind.NORMAL, null, PaymentPlan.PAY_AT_CHECKOUT, null, null, null, null, null);

        assertThat(service.create(r).roomId()).isEqualTo("r1");
    }

    @Test
    void aWalkInIsRefusedARoomSomebodyIsStillIn() {
        inRoom(stay("dep", "r1", today.minusDays(2), today, BookingStatus.CHECKED_IN));
        CreateBookingRequest r = new CreateBookingRequest(null, new GuestRequest("Walk In", "+2348011112222", null, null, null, null, null, null),
                "t1", "r1", today, today.plusDays(1), 1, 0, PriceKind.NORMAL, null, PaymentPlan.PAY_IN_FULL, null, null, null, true, null);

        assertThatThrownBy(() -> service.create(r)).isInstanceOf(ConflictException.class).hasMessageContaining("still has a guest checked in (Guest dep)");
        org.mockito.Mockito.verify(bookings, org.mockito.Mockito.never()).save(any());
    }

    @Test
    void anOverstayRoomCantBeGivenToTodaysArrival() {
        inRoom(stay("over", "r1", today.minusDays(3), today.minusDays(1), BookingStatus.CHECKED_IN));
        CreateBookingRequest r = new CreateBookingRequest(null, new GuestRequest("Ifeoma Nwachukwu", "+2348011112222", null, null, null, null, null, null),
                "t1", "r1", today, today.plusDays(1), 1, 0, PriceKind.NORMAL, null, PaymentPlan.PAY_AT_CHECKOUT, null, null, null, null, null);

        assertThatThrownBy(() -> service.create(r)).isInstanceOf(ConflictException.class).hasMessageContaining("still has a guest checked in (Guest over)");
    }

    @Test
    void movingAnInHouseGuestIntoAnOccupiedRoomIsRefused() {
        com.hospio.reservation.entity.Booking mover = stay("mv", "r2", today.minusDays(1), today.plusDays(2), BookingStatus.CHECKED_IN);
        when(bookings.findById("mv")).thenReturn(Optional.of(mover));
        when(property.room("r1")).thenReturn(new PropertyClient.RoomSummary("r1", "101", "t1", "Standard", true));
        inRoom(stay("dep", "r1", today.minusDays(2), today, BookingStatus.CHECKED_IN));

        assertThatThrownBy(() -> service.change("mv", new com.hospio.reservation.dto.ChangeBookingRequest(null, null, "r1", null, null)))
                .isInstanceOf(ConflictException.class).hasMessageContaining("Room 101 still has a guest checked in (Guest dep)");
    }

    @Test
    void availabilityKeepsOccupiedRoomsOutOfFreeAndShowsTurnover() {
        when(property.rooms("h1", "t1")).thenReturn(List.of(
                new PropertyClient.RoomSummary("r1", "101", "t1", "Standard", true),
                new PropertyClient.RoomSummary("r2", "102", "t1", "Standard", true),
                new PropertyClient.RoomSummary("r3", "103", "t1", "Standard", true),
                new PropertyClient.RoomSummary("r4", "104", "t1", "Standard", true)));
        var dueOut = stay("dep", "r1", today.minusDays(1), today, BookingStatus.CHECKED_IN);
        var over = stay("over", "r2", today.minusDays(3), today.minusDays(1), BookingStatus.CHECKED_IN);
        var staying = stay("stay", "r3", today.minusDays(1), today.plusDays(3), BookingStatus.CHECKED_IN);
        when(bookings.activeWithRoom(eq("h1"), any(), any())).thenReturn(List.of(dueOut, over, staying));
        when(bookings.activeOnType(eq("h1"), eq("t1"), any(), any())).thenReturn(List.of(dueOut, over, staying));

        var a = service.availability("t1", today, today.plusDays(1));

        assertThat(a.freeRoomIds()).containsExactly("r4");
        assertThat(a.turnoverRoomIds()).containsExactly("r1");
        assertThat(a.booked()).isEqualTo(2);
        assertThat(a.available()).isEqualTo(2);
    }

    private CreateBookingRequest request(PriceKind kind, BigDecimal special) {
        return new CreateBookingRequest(null, new GuestRequest("Ifeoma Nwachukwu", "+2348011112222", null, null, null, null, null, null),
                "t1", null, in, out, 2, 0, kind, special, PaymentPlan.DEPOSIT_50, null, null, null, null, "15:30");
    }

    private void signInAs(Role role, AccessLevel level) {
        HospioUserDetails user = new HospioUserDetails("u1", "x@y.ng", "Victor", "b1", List.of("h1"), Map.of(role, level), JwtUtil.SCOPE_HOTEL);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities()));
        TenantContext.set(Tenant.fromUser(user, "h1"));
    }

    // ---- the booking row is locked and its status read again before any change (scale audit 2026-11-13) ------------------------

    private static final com.hospio.reservation.dto.CheckInRequest NO_EXTRAS = new com.hospio.reservation.dto.CheckInRequest(null, null, null, null, null, null, null);

    @Test
    void aSecondCheckInOfTheSameBookingIsRefusedAfterTheLockAndCountsTheStayOnce() {
        var b = arrivalIn("r1");
        // The first desk's check-in commits while the second waits on the row: the second reads it fresh, already checked in.
        var checkedIn = stay("b1", "r1", b.getCheckInDate(), b.getCheckOutDate(), BookingStatus.CHECKED_IN);
        org.mockito.Mockito.doReturn(Optional.of(checkedIn)).when(bookings).lockById("b1");

        assertThatThrownBy(() -> service.checkIn("b1", NO_EXTRAS))
                .isInstanceOf(com.hospio.common.exception.BadRequestException.class).hasMessage("This booking is checked in");
        org.mockito.Mockito.verify(guests, org.mockito.Mockito.never()).countStay(any());
        org.mockito.Mockito.verifyNoInteractions(events);
    }

    @Test
    void checkInReadsPropertyAndHousekeepingBeforeTheRowLock() {
        arrivalIn("r1");
        when(readiness.isDirty("h1", "r1")).thenReturn(false);
        var order = org.mockito.Mockito.inOrder(property, readiness, bookings);

        service.checkIn("b1", NO_EXTRAS);

        order.verify(property).room("r1");
        order.verify(readiness).isDirty("h1", "r1");
        order.verify(bookings).lockById("b1");
        order.verify(bookings).lock("room:r1");
    }

    @Test
    void aBookingChangedBetweenTheReadAndTheLockIsAConflict() {
        var b = arrivalIn("r1");
        var moved = stay("b1", "r2", b.getCheckInDate(), b.getCheckOutDate(), BookingStatus.BOOKED);
        org.mockito.Mockito.doReturn(Optional.of(moved)).when(bookings).lockById("b1");

        assertThatThrownBy(() -> service.checkIn("b1", NO_EXTRAS))
                .isInstanceOf(ConflictException.class).hasMessage("Someone else changed this booking just now; reload and try again");
    }

    @Test
    void cancellingAGuestWhoWasCheckedInMeanwhileIsRefused() {
        var b = arrivalIn("r1");
        org.mockito.Mockito.doReturn(Optional.of(stay("b1", "r1", b.getCheckInDate(), b.getCheckOutDate(), BookingStatus.CHECKED_IN))).when(bookings).lockById("b1");

        assertThatThrownBy(() -> service.cancel("b1", new com.hospio.reservation.dto.CancelBookingRequest("no longer coming", false)))
                .isInstanceOf(com.hospio.common.exception.BadRequestException.class).hasMessage("The guest is already in; check them out instead");
        org.mockito.Mockito.verify(bookings, org.mockito.Mockito.never()).save(any());
    }

    @Test
    void aSecondCheckOutIsRefusedAfterTheLock() {
        var b = arrivalIn("r1");
        org.mockito.Mockito.doReturn(Optional.of(stay("b1", "r1", b.getCheckInDate(), b.getCheckOutDate(), BookingStatus.CHECKED_OUT))).when(bookings).lockById("b1");

        assertThatThrownBy(() -> service.checkOut("b1", new com.hospio.reservation.dto.CheckOutRequest(null, null, null)))
                .isInstanceOf(com.hospio.common.exception.BadRequestException.class).hasMessage("The guest is not checked in");
        org.mockito.Mockito.verifyNoInteractions(events);
    }

    @Test
    void movingToARoomOfAnotherTypeLocksBothTypesInOrder() {
        com.hospio.reservation.entity.Booking b = stay("mv", "r1", today.plusDays(1), today.plusDays(3), BookingStatus.BOOKED);
        b.setRoomTypeId("t2");
        when(bookings.findById("mv")).thenReturn(Optional.of(b));
        when(property.room("r1")).thenReturn(new PropertyClient.RoomSummary("r1", "101", "t1", "Standard", true));
        var order = org.mockito.Mockito.inOrder(property, bookings);

        service.change("mv", new com.hospio.reservation.dto.ChangeBookingRequest(null, null, "r1", null, null));

        order.verify(property).rooms("h1", "t1");
        order.verify(bookings).lockById("mv");
        order.verify(bookings).lock("h1:t1");
        order.verify(bookings).lock("h1:t2");
        org.mockito.Mockito.verify(bookings, org.mockito.Mockito.never()).lock("h1:null");
        assertThat(b.getRoomTypeId()).isEqualTo("t1");
    }

    @Test
    void theNightAuditLeavesAGuestCheckedInMeanwhileAlone() {
        when(bookings.unresolvedArrivalIds("h1", today)).thenReturn(List.of("a", "b"));
        org.mockito.Mockito.doReturn(Optional.of(stay("a", "r1", today, today.plusDays(1), BookingStatus.CHECKED_IN))).when(bookings).lockById("a");
        org.mockito.Mockito.doReturn(Optional.of(stay("b", "r2", today, today.plusDays(1), BookingStatus.BOOKED))).when(bookings).lockById("b");

        assertThat(service.markNoShows("h1", today)).isEqualTo(1);
        org.mockito.Mockito.verify(bookings, org.mockito.Mockito.times(1)).save(any());
    }

    @Test
    void thePlainListRefusesASpanOverThreeMonths() {
        assertThatThrownBy(() -> service.list(today, today.plusDays(94), null))
                .isInstanceOf(com.hospio.common.exception.BadRequestException.class)
                .hasMessage("Choose at most 93 days between from and to, or ask for a page");
        when(bookings.between(any(), any(), any(), any())).thenReturn(List.of());
        assertThat(service.list(today, today.plusDays(93), null)).isEmpty();
    }
}
