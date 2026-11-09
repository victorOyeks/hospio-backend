package com.hospio.reservation.dto;

/** Check in: assign the room if not yet assigned; optional contact for the stay summary; allow without payment (manager); {@code overrideDirty} lets a manager check into a room housekeeping hasn't cleaned. */
public record CheckInRequest(String roomId, String guestEmail, String guestPhone, Boolean withoutPayment, String idType, String idNumber,
                             Boolean overrideDirty) {
}
