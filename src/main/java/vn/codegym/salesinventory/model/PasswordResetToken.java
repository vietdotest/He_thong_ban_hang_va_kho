package vn.codegym.salesinventory.model;

import java.time.Instant;

/**
 * S1-03: Chứa mã đặt lại mật khẩu đã băm, thời hạn và trạng thái sử dụng.
 */
public record PasswordResetToken(long id, long userId, Instant expiresAt, Instant usedAt) {}
