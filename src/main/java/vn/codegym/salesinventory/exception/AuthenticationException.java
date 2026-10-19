package vn.codegym.salesinventory.exception;

/**
 * S1-01: Báo lỗi xử lý xác thực để tầng giao diện trả thông báo phù hợp.
 */
public final class AuthenticationException extends RuntimeException {

    public AuthenticationException(String message, Throwable cause) {
        super(message, cause);
    }
}
