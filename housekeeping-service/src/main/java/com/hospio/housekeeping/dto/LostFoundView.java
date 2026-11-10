package com.hospio.housekeeping.dto;

import com.hospio.housekeeping.entity.LostFoundItem;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * One lost & found item as the screens read it. {@code disposableFrom} is the first Lagos day a held item may be disposed of (found day
 * plus the hotel's hold days); {@code hasPhoto} says whether {@code GET /lost-found/{id}/photo} has bytes.
 */
public record LostFoundView(String id, String hotelId, String description, String details, String foundWhere, String roomId, String roomNumber,
                            String foundBy, String foundByName, Instant foundAt, boolean hasPhoto, String status, String returnedTo,
                            Instant returnedAt, String returnedByName, String returnIdNote, String returnHow, Instant disposedAt,
                            String disposedByName, String disposeReason, Instant frontOfficeToldAt, LocalDate disposableFrom, int holdDays) {

    public static final ZoneId LAGOS = ZoneId.of("Africa/Lagos");

    public static LostFoundView of(LostFoundItem i, int holdDays) {
        return new LostFoundView(i.getId(), i.getHotelId(), i.getDescription(), i.getDetails(), i.getFoundWhere(), i.getRoomId(), i.getRoomNumber(),
                i.getFoundBy(), i.getFoundByName(), i.getFoundAt(), i.getPhotoKey() != null, i.getStatus().name(), i.getReturnedTo(),
                i.getReturnedAt(), i.getReturnedByName(), i.getReturnIdNote(), i.getReturnHow(), i.getDisposedAt(), i.getDisposedByName(),
                i.getDisposeReason(), i.getFrontOfficeToldAt(), disposableFrom(i.getFoundAt(), holdDays), holdDays);
    }

    public static LocalDate disposableFrom(Instant foundAt, int holdDays) {
        return (foundAt == null ? Instant.now() : foundAt).atZone(LAGOS).toLocalDate().plusDays(holdDays);
    }
}
