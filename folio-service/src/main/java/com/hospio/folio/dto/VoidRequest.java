package com.hospio.folio.dto;

import jakarta.validation.constraints.NotBlank;

public record VoidRequest(@NotBlank String reason) {
}
