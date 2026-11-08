package com.hospio.identity.dto;

import com.hospio.common.enums.AccessLevel;
import com.hospio.common.enums.Role;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** Add a person and their first role. A temporary password is generated and returned once. */
public record CreateUserRequest(@NotBlank String fullName, @NotBlank @Email String email, String phone,
                                @NotNull Role role, AccessLevel level, String hotelId, String language) {
}
