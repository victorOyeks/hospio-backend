package com.hospio.folio.dto;

import jakarta.validation.constraints.NotBlank;

public record MemberRequest(@NotBlank String fullName, String staffRef, String phone, String email) {
}
