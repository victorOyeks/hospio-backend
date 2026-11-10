package com.hospio.reservation.dto;

import com.hospio.reservation.entity.Guest;
import java.time.Instant;
import java.time.LocalDate;

/** A guest in the directory: the profile plus whether they are in house and their last stay. */
public record GuestRow(String id, String fullName, String phone, String email, String idType, String idNumber, String nationality,
                       String preferences, String notes, boolean blacklisted, int stays, Instant createdAt, boolean inHouse, LocalDate lastStay) {

    public static GuestRow of(Guest g, boolean inHouse, LocalDate lastStay) {
        return new GuestRow(g.getId(), g.getFullName(), g.getPhone(), g.getEmail(), g.getIdType(), g.getIdNumber(), g.getNationality(),
                g.getPreferences(), g.getNotes(), g.isBlacklisted(), g.getStays(), g.getCreatedAt(), inHouse, lastStay);
    }
}
