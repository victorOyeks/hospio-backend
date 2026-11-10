package com.hospio.identity.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

/** Requests and answers of shared devices and the lock screen (docs/shared-devices.md). */
public final class SharedDeviceDtos {

    private SharedDeviceDtos() {
    }

    /** "Use this device as a shared device": its name, hotel, the roles it is for and how long it waits before locking. */
    public record Setup(@NotBlank @Size(min = 2, max = 40, message = "Name the device in 2 to 40 characters") String name,
                        @NotBlank String hotelId,
                        @NotEmpty(message = "Pick at least one role for the device") List<String> roles,
                        @Min(value = 1, message = "Auto-lock is 1 to 60 minutes") @Max(value = 60, message = "Auto-lock is 1 to 60 minutes")
                        Integer autoLockMinutes) {
    }

    /** A manager renames a device, changes its roles or its auto-lock time; only the fields sent change. */
    public record Change(@Size(min = 2, max = 40, message = "Name the device in 2 to 40 characters") String name,
                         List<String> roles,
                         @Min(value = 1, message = "Auto-lock is 1 to 60 minutes") @Max(value = 60, message = "Auto-lock is 1 to 60 minutes")
                         Integer autoLockMinutes) {
    }

    /** A device as Hotel setup lists it. */
    public record Device(String id, String name, String hotelId, String hotelName, List<String> roles, int autoLockMinutes,
                         Instant lastUsedAt, String lastUserName, String createdByName, Instant createdAt) {
    }

    /** Setup answers once with the device token; only its hash is kept. */
    public record SetupResult(Device device, String deviceToken) {
    }

    /** The lock screen: the device, its hotel and the people who can unlock it (a PIN and a role that fits). */
    public record LockScreen(String deviceId, String name, String hotelId, String hotelName, List<String> roles, int autoLockMinutes,
                             List<Person> people) {
    }

    /** One tile: {@code lockedUntil} is set while their PIN is locked after wrong tries. */
    public record Person(String userId, String fullName, String jobTitle, List<String> roles, Instant lockedUntil) {
    }

    /** Lock the device: the session's refresh token ends; {@code reason} is "button" or "idle". */
    public record Lock(String refreshToken, String reason) {
    }
}
