package vn.codegym.salesinventory.dto;

public record UserManagementResult(Status status, Long userId) {
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
