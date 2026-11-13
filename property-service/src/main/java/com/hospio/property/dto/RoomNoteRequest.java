package com.hospio.property.dto;

import jakarta.validation.constraints.Size;

/** The room's board note (Rooms → Note for housekeeping); blank clears it. */
public record RoomNoteRequest(@Size(max = 500, message = "Keep the note under 500 characters") String note) {
}
