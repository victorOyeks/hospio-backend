package com.hospio.reservation.service;

import com.hospio.common.enums.BookingStatus;
import com.hospio.common.enums.PaymentPlan;
import com.hospio.common.enums.PriceKind;
import com.hospio.common.exception.BadRequestException;
import com.hospio.common.exception.ConflictException;
import com.hospio.common.exception.ForbiddenException;
import com.hospio.common.exception.ResourceNotFoundException;
import com.hospio.common.kafka.EventPublisher;
import com.hospio.common.kafka.KafkaTopics;
import com.hospio.common.kafka.events.BookingCancelledEvent;
import com.hospio.common.kafka.events.BookingChangedEvent;
import com.hospio.common.kafka.events.BookingCreatedEvent;
import com.hospio.common.kafka.events.GuestCheckedInEvent;
import com.hospio.common.kafka.events.GuestCheckedOutEvent;
import com.hospio.common.security.CurrentUser;
import com.hospio.common.security.HospioUserDetails;
import com.hospio.common.tenancy.TenantContext;
import com.hospio.common.util.Money;
import com.hospio.reservation.client.PropertyClient;
import com.hospio.reservation.client.RoomReadiness;
import com.hospio.reservation.dto.DaySummary;
import com.hospio.reservation.dto.AvailabilityResponse;
import com.hospio.reservation.dto.BookingResponse;
import com.hospio.reservation.dto.CancelBookingRequest;
import com.hospio.reservation.dto.ChangeBookingRequest;
import com.hospio.reservation.dto.CheckInRequest;
import com.hospio.reservation.dto.CheckOutRequest;
import com.hospio.reservation.dto.CreateBookingRequest;
import com.hospio.reservation.dto.TodayResponse;
import com.hospio.reservation.entity.Booking;
import com.hospio.reservation.entity.Guest;
import com.hospio.reservation.repository.BookingRepository;
import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Bookings, availability, check-in and check-out. Reservations alone owns availability: every
 * booking or change takes a per-hotel-and-type advisory lock so two desks can never double-book.
 */
@Service
@RequiredArgsConstructor
public class BookingService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String REF_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    private final BookingRepository bookings;
    private final GuestService guestService;
    private final PropertyClient property;
    private final EventPublisher events;
    private final RoomReadiness readiness;

    @Transactional(readOnly = true)
    public TodayResponse today() {
        String hotelId = TenantContext.requireHotel();
        LocalDate date = Occupancy.today();
        List<Booking> all = bookings.today(hotelId, date);
        return new TodayResponse(
                all.stream().filter(b -> b.getStatus() == BookingStatus.BOOKED && date.equals(b.getCheckInDate())).map(BookingResponse::of).toList(),
                all.stream().filter(b -> b.getStatus() == BookingStatus.CHECKED_IN && date.equals(b.getCheckOutDate())).map(BookingResponse::of).toList(),
                all.stream().filter(b -> b.getStatus() == BookingStatus.CHECKED_IN).map(BookingResponse::of).toList());
    }

    @Transactional(readOnly = true)
    public List<BookingResponse> list(LocalDate from, LocalDate to, BookingStatus status) {
        LocalDate f = from == null ? Occupancy.today().minusDays(7) : from;
        LocalDate t = to == null ? Occupancy.today().plusDays(30) : to;
        return bookings.between(TenantContext.requireHotel(), f, t, status).stream().map(BookingResponse::of).toList();
    }

    @Transactional(readOnly = true)
    public BookingResponse get(String id) {
        Booking b = find(id);
        BookingResponse r = BookingResponse.of(b);
        if (b.getGuestId() == null) {
            return r;
        }
        try {
            var g = guestService.get(b.getGuestId());
            return r.withGuest(g.getPhone(), g.getEmail());
        } catch (RuntimeException e) {
            return r;
        }
    }

    @Transactional(readOnly = true)
    public List<BookingResponse> forGuest(String guestId) {
        return bookings.findByGuestIdOrderByCheckInDateDesc(guestId).stream().map(BookingResponse::of).toList();
    }

    @Transactional(readOnly = true)
    public AvailabilityResponse availability(String roomTypeId, LocalDate from, LocalDate to) {
        validateDates(from, to);
        String hotelId = TenantContext.requireHotel();
        LocalDate today = Occupancy.today();
        List<PropertyClient.RoomSummary> rooms = property.rooms(hotelId, roomTypeId).stream().filter(PropertyClient.RoomSummary::inService).toList();
        long booked = Occupancy.countBlocking(bookings.activeOnType(hotelId, roomTypeId, from, to), from, to, today, null);
        Map<String, List<Booking>> byRoom = bookings.activeWithRoom(hotelId, from, to).stream().collect(Collectors.groupingBy(Booking::getRoomId));
        List<String> free = new ArrayList<>();
        List<String> turnover = new ArrayList<>();
        for (PropertyClient.RoomSummary room : rooms) {
            switch (Occupancy.state(byRoom.getOrDefault(room.id(), List.of()), room.id(), from, to, today)) {
                case FREE -> free.add(room.id());
                case TURNOVER -> turnover.add(room.id());
                case TAKEN -> {
                }
            }
        }
        String typeName = rooms.isEmpty() ? property.roomTypeName(roomTypeId) : rooms.get(0).roomTypeName();
        return new AvailabilityResponse(roomTypeId, typeName, rooms.size(), booked, Math.max(0, rooms.size() - booked),
                property.price(roomTypeId, from), free, turnover);
    }

    @Transactional
    public BookingResponse create(CreateBookingRequest request) {
        validateDates(request.checkInDate(), request.checkOutDate());
        HospioUserDetails user = CurrentUser.require();
        String hotelId = TenantContext.requireHotel();
        PriceKind kind = request.priceKind() == null ? PriceKind.NORMAL : request.priceKind();
        if (kind != PriceKind.NORMAL && !user.isManagerOrOwner()) {
            throw new ForbiddenException("Special and complimentary prices are for managers");
        }
        PaymentPlan plan = request.paymentPlan() == null ? PaymentPlan.PAY_IN_FULL : request.paymentPlan();
        if (plan == PaymentPlan.ORGANIZATION && request.organizationId() == null) {
            throw new BadRequestException("Choose the organization this stay is billed to");
        }
        Guest guest = request.guestId() != null ? guestService.get(request.guestId())
                : guestService.findOrCreate(requireGuest(request));
        if (guest.isBlacklisted() && !user.isManagerOrOwner()) {
            throw new ForbiddenException("This guest is blacklisted. A manager has to book them");
        }

        bookings.lock(hotelId + ":" + request.roomTypeId());
        List<PropertyClient.RoomSummary> rooms = property.rooms(hotelId, request.roomTypeId()).stream().filter(PropertyClient.RoomSummary::inService).toList();
        if (rooms.isEmpty()) {
            throw new BadRequestException("No rooms of that type are in service");
        }
        PropertyClient.RoomSummary room = null;
        if (request.roomId() != null) {
            room = rooms.stream().filter(r -> r.id().equals(request.roomId())).findFirst()
                    .orElseThrow(() -> new BadRequestException("That room is not of this type or is out of order"));
            // A walk-in is checked in straight after: refuse a room somebody is still in before the booking (and any payment) is taken.
            boolean walkIn = Boolean.TRUE.equals(request.walkIn());
            if (walkIn && request.checkInDate().isAfter(Occupancy.today())) {
                throw new BadRequestException("A walk-in arrives today");
            }
            requireRoomFree(null, room.id(), room.number(), request.checkInDate(), request.checkOutDate(), walkIn);
        }
        long overlapping = Occupancy.countBlocking(bookings.activeOnType(hotelId, request.roomTypeId(), request.checkInDate(), request.checkOutDate()),
                request.checkInDate(), request.checkOutDate(), Occupancy.today(), null);
        if (overlapping >= rooms.size()) {
            throw new ConflictException("No " + rooms.get(0).roomTypeName() + " room is free for those nights");
        }

        BigDecimal nightly = switch (kind) {
            case NORMAL -> property.price(request.roomTypeId(), request.checkInDate());
            case SPECIAL -> {
                if (!Money.isPositive(request.specialRate())) {
                    throw new BadRequestException("Enter the special price");
                }
                yield Money.of(request.specialRate());
            }
            case COMPLIMENTARY -> BigDecimal.ZERO;
        };
        Booking booking = Booking.builder()
                .reference(newReference()).guestId(guest.getId()).guestName(guest.getFullName())
                .roomTypeId(request.roomTypeId()).roomTypeName(rooms.get(0).roomTypeName())
                .roomId(room == null ? null : room.id()).roomNumber(room == null ? null : room.number())
                .checkInDate(request.checkInDate()).checkOutDate(request.checkOutDate())
                .adults(request.adults() == null ? 1 : request.adults()).children(request.children() == null ? 0 : request.children())
                .priceKind(kind).nightlyRate(Money.of(nightly)).paymentPlan(plan).organizationId(request.organizationId())
                .source(request.source() == null ? "FRONT_DESK" : request.source()).notes(request.notes()).createdBy(user.getUserId())
                .build();
        booking.setTotalAmount(Money.of(nightly.multiply(BigDecimal.valueOf(booking.nights()))));
        bookings.save(booking);
        events.publish(KafkaTopics.BOOKING_CREATED, BookingCreatedEvent.builder()
                .bookingId(booking.getId()).guestId(guest.getId()).guestName(guest.getFullName())
                .roomId(booking.getRoomId()).roomNumber(booking.getRoomNumber()).checkInDate(booking.getCheckInDate())
                .checkOutDate(booking.getCheckOutDate()).amount(booking.getTotalAmount()).paymentPlan(plan.name())
                .priceKind(kind.name()).organizationId(request.organizationId()).build());
        return BookingResponse.of(booking);
    }

    @Transactional
    public BookingResponse change(String id, ChangeBookingRequest request) {
        Booking b = find(id);
        if (!b.isActive()) {
            throw new BadRequestException("This booking is " + b.getStatus().name().toLowerCase());
        }
        LocalDate in = request.checkInDate() == null ? b.getCheckInDate() : request.checkInDate();
        LocalDate out = request.checkOutDate() == null ? b.getCheckOutDate() : request.checkOutDate();
        if (b.getStatus() == BookingStatus.CHECKED_IN && !in.equals(b.getCheckInDate())) {
            throw new BadRequestException("The guest is already in; only the check-out date or room can change");
        }
        validateDates(in, out, b.getStatus() == BookingStatus.CHECKED_IN);
        String typeId = request.roomTypeId() == null ? b.getRoomTypeId() : request.roomTypeId();
        bookings.lock(b.getHotelId() + ":" + typeId);
        PropertyClient.RoomSummary room = null;
        if (request.roomId() != null) {
            room = property.room(request.roomId());
            if (!room.inService()) {
                throw new BadRequestException("Room " + room.number() + " is out of order");
            }
            typeId = room.roomTypeId();
            // An in-house guest moves into the room now: nobody else may be checked in to it, whatever their dates.
            requireRoomFree(b.getId(), room.id(), room.number(), in, out, b.getStatus() == BookingStatus.CHECKED_IN && !room.id().equals(b.getRoomId()));
        } else if (b.getRoomId() != null) {
            requireRoomFree(b.getId(), b.getRoomId(), b.getRoomNumber(), in, out, false);
        }
        long rooms = property.rooms(b.getHotelId(), typeId).stream().filter(PropertyClient.RoomSummary::inService).count();
        if (Occupancy.countBlocking(bookings.activeOnType(b.getHotelId(), typeId, in, out), in, out, Occupancy.today(), b.getId()) >= rooms) {
            throw new ConflictException("No room of that type is free for those nights");
        }
        String change = describeChange(b, in, out, room);
        b.setCheckInDate(in);
        b.setCheckOutDate(out);
        if (room != null) {
            b.setRoomId(room.id());
            b.setRoomNumber(room.number());
            b.setRoomTypeId(room.roomTypeId());
            b.setRoomTypeName(room.roomTypeName());
            if (b.getPriceKind() == PriceKind.NORMAL) {
                b.setNightlyRate(property.price(room.roomTypeId(), in));
            }
        }
        b.setTotalAmount(Money.of(b.getNightlyRate().multiply(BigDecimal.valueOf(b.nights()))));
        saveInHouse(b);
        events.publish(KafkaTopics.BOOKING_CHANGED, BookingChangedEvent.builder()
                .bookingId(b.getId()).guestName(b.getGuestName()).change(change + (request.reason() == null ? "" : " (" + request.reason() + ")"))
                .roomId(b.getRoomId()).roomNumber(b.getRoomNumber()).checkInDate(in).checkOutDate(out).build());
        return BookingResponse.of(b);
    }

    @Transactional
    public BookingResponse checkIn(String id, CheckInRequest request) {
        Booking b = find(id);
        if (b.getStatus() != BookingStatus.BOOKED) {
            throw new BadRequestException("This booking is " + b.getStatus().name().toLowerCase().replace('_', ' '));
        }
        if (Occupancy.today().isBefore(b.getCheckInDate())) {
            throw new BadRequestException("Check-in is on " + b.getCheckInDate());
        }
        HospioUserDetails user = CurrentUser.require();
        if (Boolean.TRUE.equals(request.withoutPayment()) && !user.isManagerOrOwner()
                && b.getPaymentPlan() != PaymentPlan.PAY_AT_CHECKOUT && b.getPaymentPlan() != PaymentPlan.ORGANIZATION) {
            throw new ForbiddenException("Only a manager can check a guest in without payment");
        }
        if (b.getRoomId() == null || request.roomId() != null) {
            String roomId = request.roomId() == null ? b.getRoomId() : request.roomId();
            if (roomId == null) {
                throw new BadRequestException("Choose a room for the guest");
            }
            PropertyClient.RoomSummary room = property.room(roomId);
            if (!room.inService()) {
                throw new BadRequestException("Room " + room.number() + " is out of order");
            }
            bookings.lock(b.getHotelId() + ":" + room.roomTypeId());
            requireRoomFree(b.getId(), room.id(), room.number(), b.getCheckInDate(), b.getCheckOutDate(), true);
            b.setRoomId(room.id());
            b.setRoomNumber(room.number());
        } else if (!property.room(b.getRoomId()).inService()) {
            // The room given at booking went out of order since (a work order): the desk picks another room.
            throw new BadRequestException("Room " + b.getRoomNumber() + " is out of order · choose another room");
        } else {
            // The room given at booking (or a same-day turnover): the guest before must have been checked out.
            requireRoomFree(b.getId(), b.getRoomId(), b.getRoomNumber(), b.getCheckInDate(), b.getCheckOutDate(), true);
        }
        String warning = null;
        if (b.getRoomId() != null && readiness.isDirty(b.getHotelId(), b.getRoomId())) {
            warning = "Room not cleaned yet";
            if (readiness.inspectDepartures(b.getHotelId())) {
                if (!Boolean.TRUE.equals(request.overrideDirty())) {
                    throw new BadRequestException("Room " + b.getRoomNumber() + " hasn't been cleaned yet. A manager can check the guest in anyway");
                }
                if (!user.isManagerOrOwner() && !user.isPlatform()) {
                    throw new ForbiddenException("Only a manager can check a guest into a room that hasn't been cleaned");
                }
            }
        }
        Guest guest = guestService.get(b.getGuestId());
        if (request.guestEmail() != null || request.guestPhone() != null || request.idNumber() != null) {
            if (request.guestEmail() != null) {
                guest.setEmail(request.guestEmail());
            }
            if (request.guestPhone() != null) {
                guest.setPhone(request.guestPhone());
            }
            if (request.idNumber() != null) {
                guest.setIdType(request.idType());
                guest.setIdNumber(request.idNumber());
            }
        }
        guestService.countStay(guest.getId());
        b.setStatus(BookingStatus.CHECKED_IN);
        b.setCheckedInAt(Instant.now());
        saveInHouse(b);
        events.publish(KafkaTopics.GUEST_CHECKED_IN, GuestCheckedInEvent.builder()
                .bookingId(b.getId()).guestId(guest.getId()).guestName(guest.getFullName()).guestPhone(guest.getPhone())
                .guestEmail(guest.getEmail()).roomId(b.getRoomId()).roomNumber(b.getRoomNumber()).checkOutDate(b.getCheckOutDate())
                .nightlyRate(b.getNightlyRate()).nights(b.nights()).paymentPlan(b.getPaymentPlan().name()).organizationId(b.getOrganizationId()).build());
        BookingResponse response = BookingResponse.of(b);
        return warning == null ? response : response.withWarning(warning);
    }

    @Transactional
    public BookingResponse checkOut(String id, CheckOutRequest request) {
        Booking b = find(id);
        if (b.getStatus() != BookingStatus.CHECKED_IN) {
            throw new BadRequestException("The guest is not checked in");
        }
        BigDecimal balance = Money.of(request.folioBalance());
        if (balance.signum() > 0 && !Boolean.TRUE.equals(request.allowPendingPayment()) && b.getPaymentPlan() != PaymentPlan.ORGANIZATION) {
            throw new BadRequestException("There is " + balance + " still to pay. Take payment first, or check out with a pending payment");
        }
        Guest guest = guestService.get(b.getGuestId());
        b.setStatus(BookingStatus.CHECKED_OUT);
        b.setCheckedOutAt(Instant.now());
        bookings.save(b);
        events.publish(KafkaTopics.GUEST_CHECKED_OUT, GuestCheckedOutEvent.builder()
                .bookingId(b.getId()).guestId(guest.getId()).guestName(guest.getFullName()).guestPhone(guest.getPhone())
                .guestEmail(guest.getEmail()).roomId(b.getRoomId()).roomNumber(b.getRoomNumber()).folioBalance(balance)
                .summaryChannels(request.summaryChannels() == null ? List.of() : request.summaryChannels()).build());
        return BookingResponse.of(b);
    }

    @Transactional
    public BookingResponse lateCheckOut(String id, LocalTime until) {
        Booking b = find(id);
        if (b.getStatus() != BookingStatus.CHECKED_IN) {
            throw new BadRequestException("The guest is not checked in");
        }
        b.setLateCheckoutUntil(until);
        bookings.save(b);
        events.publish(KafkaTopics.BOOKING_CHANGED, BookingChangedEvent.builder()
                .bookingId(b.getId()).guestName(b.getGuestName()).change("Late check-out until " + until)
                .roomId(b.getRoomId()).roomNumber(b.getRoomNumber()).checkInDate(b.getCheckInDate()).checkOutDate(b.getCheckOutDate()).build());
        return BookingResponse.of(b);
    }

    @Transactional
    public BookingResponse cancel(String id, CancelBookingRequest request) {
        Booking b = find(id);
        if (b.getStatus() == BookingStatus.CHECKED_IN) {
            throw new BadRequestException("The guest is already in; check them out instead");
        }
        if (!b.isActive()) {
            throw new BadRequestException("This booking is already " + b.getStatus().name().toLowerCase());
        }
        b.setStatus(BookingStatus.CANCELLED);
        b.setCancelledAt(Instant.now());
        b.setCancellationReason(request.reason());
        bookings.save(b);
        events.publish(KafkaTopics.BOOKING_CANCELLED, BookingCancelledEvent.builder()
                .bookingId(b.getId()).guestName(b.getGuestName()).reason(request.reason()).refundable(request.refundable()).build());
        return BookingResponse.of(b);
    }

    /** How many bookings a room has had, in any status; counted as the system because the room may be in any hotel. */
    @Transactional(readOnly = true)
    public long bookingsOnRoom(String roomId) {
        return com.hospio.common.tenancy.TenantContext.runWith(com.hospio.common.tenancy.Tenant.system("room-bookings"),
                () -> bookings.countByRoomId(roomId));
    }

    @Transactional(readOnly = true)
    public BookingResponse activeOnRoom(String roomId) {
        return bookings.findFirstByRoomIdAndStatus(roomId, BookingStatus.CHECKED_IN).map(BookingResponse::of)
                .orElseThrow(() -> new ResourceNotFoundException("Nobody is checked in to that room"));
    }

    @Transactional(readOnly = true)
    public List<BookingResponse> inHouse(String hotelId) {
        return bookings.findByHotelIdAndStatusOrderByCheckInDate(hotelId, BookingStatus.CHECKED_IN).stream().map(BookingResponse::of).toList();
    }

    /** BOOKED arrivals for a date (and one room when given): housekeeping's "guest arrives today" and the desk's pending list. */
    @Transactional(readOnly = true)
    public List<BookingResponse> bookedArrivals(String hotelId, LocalDate date, String roomId) {
        return bookings.bookedArrivalsOn(hotelId, date).stream().filter(b -> roomId == null || roomId.equals(b.getRoomId())).map(BookingResponse::of).toList();
    }

    /** What finance's night audit needs to know about a business day. */
    @Transactional(readOnly = true)
    public DaySummary daySummary(String hotelId, LocalDate date) {
        List<Booking> pendingIn = bookings.unresolvedArrivals(hotelId, date);
        List<Booking> pendingOut = bookings.unresolvedDepartures(hotelId, date);
        List<Booking> inHouse = bookings.findByHotelIdAndStatusOrderByCheckInDate(hotelId, BookingStatus.CHECKED_IN);
        int roomsOccupied = (int) inHouse.stream().map(Booking::getRoomId).filter(java.util.Objects::nonNull).distinct().count();
        long noShows = bookings.between(hotelId, date, date, BookingStatus.NO_SHOW).stream().filter(b -> date.equals(b.getCheckInDate())).count();
        return new DaySummary(date, (int) bookings.countExpectedArrivals(hotelId, date), pendingIn.size(), (int) bookings.countDepartures(hotelId, date),
                pendingOut.size(), inHouse.size(), (int) noShows, roomsOccupied, pendingIn.stream().map(BookingService::stay).toList(),
                pendingOut.stream().map(BookingService::stay).toList(), bookings.departuresOn(hotelId, date).stream().map(BookingService::stay).toList());
    }

    /** Night audit close with markNoShows: every BOOKED arrival up to the date becomes NO_SHOW. Returns how many. */
    @Transactional
    public int markNoShows(String hotelId, LocalDate date) {
        List<Booking> pending = bookings.unresolvedArrivals(hotelId, date);
        for (Booking b : pending) {
            b.setStatus(BookingStatus.NO_SHOW);
            bookings.save(b);
            events.publish(KafkaTopics.BOOKING_CHANGED, BookingChangedEvent.builder()
                    .bookingId(b.getId()).guestName(b.getGuestName()).change("Marked no-show by the night audit")
                    .roomId(b.getRoomId()).roomNumber(b.getRoomNumber()).checkInDate(b.getCheckInDate()).checkOutDate(b.getCheckOutDate()).build());
        }
        return pending.size();
    }

    private static DaySummary.Stay stay(Booking b) {
        return new DaySummary.Stay(b.getId(), b.getReference(), b.getGuestName(), b.getRoomNumber(), b.getCheckInDate(), b.getCheckOutDate());
    }

    /**
     * Refuses a room another stay holds on a night of [in, out) (an in-house guest, overstays included, or another booking). With
     * {@code movingInNow} (check-in, walk-in, moving an in-house guest) it also refuses while anybody else is still checked in to the
     * room, even a guest due out today whose room may already be given to today's arrival. Takes the room's lock first so two desks
     * can't both pass; the partial unique index ux_bookings_room_in_house is the database's guarantee behind it.
     */
    void requireRoomFree(String selfId, String roomId, String roomNumber, LocalDate in, LocalDate out, boolean movingInNow) {
        bookings.lock("room:" + roomId);
        if (movingInNow) {
            Occupancy.inHouseOn(bookings.findByRoomIdAndStatus(roomId, BookingStatus.CHECKED_IN), roomId, selfId).ifPresent(o -> {
                throw new ConflictException(Occupancy.stillInHouse(roomNumber, o));
            });
        }
        Occupancy.firstBlocking(bookings.activeOnRoom(roomId, in, out), in, out, Occupancy.today(), selfId).ifPresent(o -> {
            throw new ConflictException(Occupancy.taken(roomNumber, o));
        });
    }

    /** Saves a stay that is (or becomes) in house, turning a clash on ux_bookings_room_in_house into the desk's refusal. */
    private void saveInHouse(Booking b) {
        if (b.getStatus() != BookingStatus.CHECKED_IN) {
            bookings.save(b);
            return;
        }
        try {
            bookings.saveAndFlush(b);
        } catch (DataIntegrityViolationException e) {
            throw new ConflictException("Room " + b.getRoomNumber() + " already has a guest checked in. Check them out first, or choose another room.");
        }
    }

    private Booking find(String id) {
        return bookings.findById(id).or(() -> bookings.findByReference(id)).orElseThrow(() -> new ResourceNotFoundException("Booking not found"));
    }

    private static com.hospio.reservation.dto.GuestRequest requireGuest(CreateBookingRequest request) {
        if (request.guest() == null || request.guest().fullName() == null || request.guest().fullName().isBlank()) {
            throw new BadRequestException("Choose a guest or enter the guest's name");
        }
        if (request.guest().phone() == null || request.guest().phone().isBlank()) {
            throw new BadRequestException("Enter the guest's phone number");
        }
        return request.guest();
    }

    private static void validateDates(LocalDate in, LocalDate out) {
        validateDates(in, out, false);
    }

    private static void validateDates(LocalDate in, LocalDate out, boolean allowPastCheckIn) {
        if (in == null || out == null) {
            throw new BadRequestException("Choose check-in and check-out dates");
        }
        if (!out.isAfter(in)) {
            throw new BadRequestException("Check-out must be after check-in");
        }
        if (!allowPastCheckIn && in.isBefore(Occupancy.today())) {
            throw new BadRequestException("Check-in can't be in the past");
        }
    }

    private static String describeChange(Booking b, LocalDate in, LocalDate out, PropertyClient.RoomSummary room) {
        List<String> parts = new ArrayList<>();
        if (!in.equals(b.getCheckInDate()) || !out.equals(b.getCheckOutDate())) {
            parts.add("dates " + in + " to " + out);
        }
        if (room != null && !room.id().equals(b.getRoomId())) {
            parts.add("room " + (b.getRoomNumber() == null ? "unassigned" : b.getRoomNumber()) + " to " + room.number());
        }
        return parts.isEmpty() ? "no change" : String.join(", ", parts);
    }

    private String newReference() {
        for (int attempt = 0; attempt < 5; attempt++) {
            StringBuilder sb = new StringBuilder("HB");
            for (int i = 0; i < 6; i++) {
                sb.append(REF_ALPHABET.charAt(RANDOM.nextInt(REF_ALPHABET.length())));
            }
            if (bookings.findByReference(sb.toString()).isEmpty()) {
                return sb.toString();
            }
        }
        throw new IllegalStateException("Could not generate a booking reference");
    }

    static Set<BookingStatus> activeStatuses() {
        return Set.of(BookingStatus.BOOKED, BookingStatus.CHECKED_IN);
    }

    static String join(List<String> parts) {
        return parts.stream().collect(Collectors.joining(", "));
    }
}
