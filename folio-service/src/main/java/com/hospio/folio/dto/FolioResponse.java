package com.hospio.folio.dto;

import com.hospio.folio.entity.Folio;
import com.hospio.folio.entity.FolioLine;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record FolioResponse(String id, String bookingId, String guestId, String guestName, String guestPhone, String guestEmail,
                            String roomId, String roomNumber, String organizationId, Folio.Status status, BigDecimal totalCharges,
                            BigDecimal totalTax, BigDecimal totalDiscounts, BigDecimal totalPayments, BigDecimal balance,
                            Instant openedAt, Instant closedAt, List<FolioLine> lines) {

    public static FolioResponse of(Folio f, List<FolioLine> lines) {
        return new FolioResponse(f.getId(), f.getBookingId(), f.getGuestId(), f.getGuestName(), f.getGuestPhone(), f.getGuestEmail(),
                f.getRoomId(), f.getRoomNumber(), f.getOrganizationId(), f.getStatus(), f.getTotalCharges(), f.getTotalTax(),
                f.getTotalDiscounts(), f.getTotalPayments(), f.getBalance(), f.getOpenedAt(), f.getClosedAt(), lines);
    }
}
