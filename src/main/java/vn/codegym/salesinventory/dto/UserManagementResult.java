package vn.codegym.salesinventory.dto;

/**
 * S1-08: Trả kết quả xử lý tài khoản và các lỗi cần hiển thị.
 */
public record UserManagementResult(Status status, Long userId) {

    // Liệt kê kết quả xử lý tài khoản để giao diện hiển thị phù hợp.
    public enum Status {
        SUCCESS,
        FORBIDDEN,
        NOT_FOUND,
        INVALID_ROLE,
        DUPLICATE_USERNAME,
        DUPLICATE_EMAIL,
        DUPLICATE_PHONE,
        STALE_UPDATE,
        SELF_PROTECTION,
        EMAIL_DELIVERY_FAILED
    }

    public static UserManagementResult success(long userId) {
        return new UserManagementResult(Status.SUCCESS, userId);
    }

    public static UserManagementResult failure(Status status) {
        return new UserManagementResult(status, null);
    }
}
