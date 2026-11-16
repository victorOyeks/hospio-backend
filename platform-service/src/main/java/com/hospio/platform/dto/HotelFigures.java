package com.hospio.platform.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;

/**
 * What each service answers on {@code GET /internal/admin/hotel-overview?hotelId=} for the Hotels tab, mirroring its
 * {@code HotelOverviewResponse} field for field (so {@link AdminResponses.HotelOverview#raw()} passes them on unchanged).
 * Numbers are boxed: a field the service did not send reads as null, and the tab falls back for it.
 */
public final class HotelFigures {

    private HotelFigures() {
    }

    /** property-service: rooms, how many are out of order, active room types. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Property(Long rooms, Long outOfOrder, Long roomTypes) {
    }

    /** reservation-service: occupancy today, arrivals, departures, who is in house, bookings made in the last 7 days. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Reservation(BigDecimal occupancyPct, Long arrivalsToday, Long departuresToday, Long inHouse, Long bookingsLast7Days) {
    }

    /** folio-service: open folios and what they owe in total. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Folio(Long openFolios, BigDecimal openBalance) {
    }

    /** payment-service: payments today and pending payments. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Payment(String hotelId, PaymentsToday paymentsToday, Long pendingPayments) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PaymentsToday(Long count, BigDecimal gross) {
    }

    /** hotel-config-service: the WhatsApp number and POS provider set up, and how much of the setup is done (0-100). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record HotelConfig(String whatsappNumber, String posProvider, Integer setupCompletePct) {
    }

    /** Every service's answer as it came, null for one that failed or was late; keyed by service as the tab has always had it. */
    public record Raw(Property property, Reservation reservation, Folio folio, Payment payment,
                      @JsonProperty("hotel-config") HotelConfig hotelConfig) {
    }
}
