package com.hospio.payment.dto;

import jakarta.validation.constraints.NotBlank;

public record TerminalRequest(@NotBlank String name, @NotBlank String provider, @NotBlank String terminalRef) {
}
