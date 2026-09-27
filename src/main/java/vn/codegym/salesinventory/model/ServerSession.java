package vn.codegym.salesinventory.model;

import java.time.Instant;

public record ServerSession(
        String id,
        long userId,
        String tokenHash,
        String username,
        String email,
        String fullName,
        UserStatus userStatus,
        Instant userLockedUntil,
        Instant createdAt,
        Instant lastActivityAt,
        Instant expiresAt,
        Instant revokedAt
) {
}
