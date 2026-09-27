package vn.codegym.salesinventory.model;

import java.time.Instant;

public record User(
        long id,
        String username,
        String email,
        String fullName,
        String passwordHash,
        UserStatus status,
        int failedLoginCount,
        Instant lockedUntil
) {
}
