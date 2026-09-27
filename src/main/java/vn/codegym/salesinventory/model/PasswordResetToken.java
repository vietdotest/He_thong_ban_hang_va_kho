package vn.codegym.salesinventory.model;

import java.time.Instant;

public record PasswordResetToken(
        long id,
        long userId,
        Instant expiresAt,
        Instant usedAt
) {
}
