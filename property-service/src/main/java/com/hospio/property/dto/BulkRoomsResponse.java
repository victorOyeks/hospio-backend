package com.hospio.property.dto;

import java.util.List;

/** How many rooms were created and which numbers were already there (or repeated in the request). */
public record BulkRoomsResponse(int created, List<String> skipped) {
}
