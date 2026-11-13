package com.hospio.reservation.service;

import com.hospio.common.enums.BookingStatus;
import com.hospio.common.exception.BadRequestException;
import com.hospio.common.util.Money;
import com.hospio.reservation.client.PropertyClient;
import com.hospio.reservation.dto.AdminNightsResponse;
import com.hospio.reservation.dto.HotelOverviewResponse;
import com.hospio.reservation.repository.BookingRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-only figures for the platform admin portal (platform-service calls these as the platform, so the
 * row-level policies see every hotel). Nothing here changes a booking.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminOverviewService {

    private final BookingRepository bookings;
    private final PropertyClient property;

    /**
     * Room nights and their value for every hotel of a business over [from, to] (both inclusive). A stay that
     * crosses the range edge counts only the nights inside it, at its nightly rate, so a month is never billed twice.
     */
    @Transactional(readOnly = true)
    public AdminNightsResponse nights(String businessId, LocalDate from, LocalDate to) {
        if (from == null || to == null || to.isBefore(from)) {
            throw new BadRequestException("Give a from and to date, from first");
        }
        // Summed in SQL over the overlapping nights only: nothing is loaded per booking.
        Object[] row = bookings.nightsInBusiness(businessId, from, to.plusDays(1)).stream().findFirst().orElse(new Object[] {0L, BigDecimal.ZERO});
        long nights = row[0] == null ? 0 : ((Number) row[0]).longValue();
        BigDecimal value = row[1] == null ? BigDecimal.ZERO : new BigDecimal(row[1].toString());
        return new AdminNightsResponse(nights, Money.of(value));
    }

    /** Today at one hotel, as counts; occupancy is checked-in stays over rooms in service (0 when the hotel has no rooms). */
    @Transactional(readOnly = true)
    public HotelOverviewResponse hotelOverview(String hotelId) {
        LocalDate today = LocalDate.now();
        long inHouse = bookings.countByHotelIdAndStatus(hotelId, BookingStatus.CHECKED_IN);
        long arrivals = bookings.countByHotelIdAndStatusAndCheckInDate(hotelId, BookingStatus.BOOKED, today);
        long departures = bookings.countByHotelIdAndStatusAndCheckOutDate(hotelId, BookingStatus.CHECKED_IN, today);
        long last7 = bookings.countByHotelIdAndCreatedAtGreaterThanEqual(hotelId, Instant.now().minus(7, ChronoUnit.DAYS));
        long rooms = roomsInService(hotelId);
        int occupancy = rooms == 0 ? 0 : (int) Math.min(100, Math.round(inHouse * 100.0 / rooms));
        return new HotelOverviewResponse(occupancy, arrivals, departures, inHouse, last7);
    }

    private long roomsInService(String hotelId) {
        try {
            List<PropertyClient.RoomSummary> rooms = property.rooms(hotelId, null);
            return rooms.stream().filter(PropertyClient.RoomSummary::inService).count();
        } catch (RuntimeException e) {
            log.warn("Could not count rooms of hotel {} for the overview: {}", hotelId, e.getMessage());
            return 0;
        }
    }
}
