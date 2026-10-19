package vn.codegym.salesinventory.model;

/**
 * S1-01, S1-08, S1-10: Liệt kê các trạng thái tài khoản như hoạt động, chờ kích hoạt hoặc bị khóa.
 */
public enum UserStatus {
    ACTIVE,
    DISABLED,
    ADMIN_LOCKED,
    PENDING_ACTIVATION
}
