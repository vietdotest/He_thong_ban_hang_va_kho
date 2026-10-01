package vn.codegym.salesinventory.model;

import java.time.Instant;
import java.util.Set;

public record ServerSession(
        String id,
        long userId,
        String tokenHash,
        String username,
        String email,
        String fullName,
        Set<String> roleCodes,
        boolean mustChangePassword,
        UserStatus userStatus,
        Instant userLockedUntil,
        Instant createdAt,
        Instant lastActivityAt,
        Instant expiresAt,
        Instant revokedAt
) {
    public ServerSession {
        roleCodes = roleCodes == null ? Set.of() : Set.copyOf(roleCodes);
    }

    public ServerSession(
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
        this(id, userId, tokenHash, username, email, fullName, Set.of(), false, userStatus,
                userLockedUntil, createdAt, lastActivityAt, expiresAt, revokedAt);
    }
}
