package vn.codegym.salesinventory.model;

import java.time.Instant;

public record User(
        long id,
        String username,
        String email,
        String fullName,
        String phone,
        String passwordHash,
        boolean mustChangePassword,
        UserStatus status,
        int failedLoginCount,
        Instant lockedUntil
) {
    public User(
            long id,
            String username,
            String email,
            String fullName,
            String passwordHash,
            UserStatus status,
            int failedLoginCount,
            Instant lockedUntil
    ) {
        this(id, username, email, fullName, null, passwordHash, false, status, failedLoginCount, lockedUntil);
    }
}
