package com.hospio.reservation.service;

import com.hospio.common.enums.BookingStatus;
import com.hospio.common.enums.Role;
import com.hospio.common.exception.BadRequestException;
import com.hospio.common.paging.Paging;
import com.hospio.common.response.PageWithCounts;
import com.hospio.common.security.CurrentUser;
import com.hospio.common.security.HospioUserDetails;
import com.hospio.common.tenancy.TenantContext;
import com.hospio.reservation.dto.BookingResponse;
import com.hospio.reservation.dto.GuestRow;
import com.hospio.reservation.entity.Booking;
import com.hospio.reservation.entity.Guest;
import com.hospio.reservation.query.BookingListQuery;
import com.hospio.reservation.query.GuestListQuery;
import com.hospio.reservation.repository.BookingRepository;
import com.hospio.reservation.repository.GuestRepository;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * The paged bookings and guests lists (docs/pagination.md). Bookings are the X-Hotel-Id hotel's, or for the owner without one the whole
 * business ({@code hotelId} narrows); each row carries the guest's phone and email. Guests are business-wide profiles.
 */
@Service
@RequiredArgsConstructor
public class ReservationListService {

    /** {@code view}: arrivals, departures, in-house, upcoming, past or all; {@code status} may list several (comma separated). */
    public record BookingParams(Integer page, Integer size, String sort, String q, String status, String view, LocalDate from, LocalDate to,
                                LocalDate arrivingFrom, LocalDate arrivingTo, String hotelId, String guestId) {
    }

    private final BookingListQuery bookingQuery;
    private final GuestListQuery guestQuery;
    private final BookingRepository bookings;
    private final GuestRepository guests;

    @Transactional(readOnly = true)
    public PageWithCounts<BookingResponse> bookings(BookingParams p) {
        HospioUserDetails user = CurrentUser.require();
        int page = Paging.page(p.page());
        int size = Paging.size(p.size());
        Sort.Order order = Paging.sort(p.sort(), Map.of("checkInDate", "checkInDate", "checkOutDate", "checkOutDate", "guestName", "guestName",
                "totalAmount", "totalAmount", "createdAt", "createdAt", "status", "status", "roomNumber", "roomNumber"), Sort.unsorted())
                .stream().findFirst().orElse(null);
        String view = blank(p.view()) == null ? null : p.view().trim().toLowerCase(Locale.ROOT);
        if (view != null && !BookingListQuery.VIEWS.contains(view)) {
            throw new BadRequestException("view is one of " + String.join(", ", BookingListQuery.VIEWS));
        }
        if (p.from() != null && p.to() != null && p.to().isBefore(p.from())) {
            throw new BadRequestException("from is after to");
        }
        BookingListQuery.Filter f = new BookingListQuery.Filter(TenantContext.requireBusiness(), hotel(user, p.hotelId()), blank(p.q()),
                statuses(p.status()), view, p.from(), p.to(), p.arrivingFrom(), p.arrivingTo(), blank(p.guestId()), LocalDate.now(Paging.LAGOS));

        long total = bookingQuery.count(f);
        List<String> ids = bookingQuery.ids(f, order == null ? null : BookingListQuery.SORTS.get(order.getProperty()),
                order != null && order.isAscending(), page, size);
        Map<String, Booking> byId = bookings.findAllById(ids).stream().collect(Collectors.toMap(Booking::getId, Function.identity()));
        List<Booking> rows = ids.stream().map(byId::get).filter(Objects::nonNull).toList();
        Map<String, Guest> people = guests.findAllById(rows.stream().map(Booking::getGuestId).distinct().toList()).stream()
                .collect(Collectors.toMap(Guest::getId, Function.identity()));
        List<BookingResponse> content = rows.stream().map(b -> {
            Guest g = people.get(b.getGuestId());
            return BookingResponse.of(b).withGuest(g == null ? null : g.getPhone(), g == null ? null : g.getEmail());
        }).toList();
        int pages = (int) Math.ceil(total / (double) size);
        return new PageWithCounts<>(content, page, size, total, pages, page == 0, page + 1 >= pages, content.isEmpty(),
                bookingQuery.counts(f), bookingQuery.totals(f));
    }

    @Transactional(readOnly = true)
    public PageWithCounts<GuestRow> guests(Integer page, Integer size, String sort, String q, String view) {
        int pg = Paging.page(page);
        int sz = Paging.size(size);
        Sort.Order order = Paging.sort(sort, Map.of("fullName", "fullName", "stays", "stays", "createdAt", "createdAt"), Sort.unsorted())
                .stream().findFirst().orElse(null);
        String v = blank(view) == null ? null : view.trim().toLowerCase(Locale.ROOT);
        if (v != null && !GuestListQuery.VIEWS.contains(v)) {
            throw new BadRequestException("view is one of " + String.join(", ", GuestListQuery.VIEWS));
        }
        GuestListQuery.Filter f = new GuestListQuery.Filter(TenantContext.requireBusiness(), TenantContext.current().map(t -> t.hotelId()).orElse(null),
                blank(q), v);
        long total = guestQuery.count(f);
        List<GuestListQuery.Row> found = guestQuery.page(f, order == null ? null : GuestListQuery.SORTS.get(order.getProperty()),
                order != null && order.isAscending(), pg, sz);
        Map<String, Guest> byId = guests.findAllById(found.stream().map(GuestListQuery.Row::id).toList()).stream()
                .collect(Collectors.toMap(Guest::getId, Function.identity()));
        List<GuestRow> content = found.stream().filter(r -> byId.containsKey(r.id())).map(r -> GuestRow.of(byId.get(r.id()), r.inHouse(), r.lastStay()))
                .toList();
        int pages = (int) Math.ceil(total / (double) sz);
        return new PageWithCounts<>(content, pg, sz, total, pages, pg == 0, pg + 1 >= pages, content.isEmpty(), guestQuery.counts(f), Map.of());
    }

    /** The X-Hotel-Id hotel; without one only the owner reads the whole business ({@code hotelId} narrows to one hotel). */
    static String hotel(HospioUserDetails user, String hotelId) {
        String hotel = TenantContext.require().hotelId();
        if (hotel != null) {
            return hotel;
        }
        if (!user.has(Role.OWNER)) {
            throw new BadRequestException("Choose a hotel first (X-Hotel-Id header)");
        }
        return blank(hotelId);
    }

    public static String statuses(String status) {
        if (blank(status) == null) {
            return null;
        }
        return Arrays.stream(status.split(",")).map(String::trim).filter(x -> !x.isEmpty()).map(x -> {
            try {
                return BookingStatus.valueOf(x.toUpperCase(Locale.ROOT)).name();
            } catch (IllegalArgumentException e) {
                throw new BadRequestException("Unknown booking status: " + x);
            }
        }).distinct().collect(Collectors.joining(","));
    }

    private static String blank(String s) {
        return StringUtils.hasText(s) ? s.trim() : null;
    }
}
