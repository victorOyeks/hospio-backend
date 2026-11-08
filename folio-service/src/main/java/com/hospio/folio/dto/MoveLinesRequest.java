package com.hospio.folio.dto;

import jakarta.validation.constraints.NotEmpty;
import java.util.List;

/** Split a folio (target null → a new folio for the same stay) or move charges to another room's folio. */
public record MoveLinesRequest(@NotEmpty List<String> lineIds, String targetFolioId, String targetRoomId) {
}
