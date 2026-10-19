package vn.codegym.salesinventory.validation;

/**
 * Dùng chung: Báo dữ liệu đã thay đổi hoặc yêu cầu trùng; lần thử lại cần giữ mã yêu cầu ban đầu.
 */
public final class CommandConflictException extends IllegalArgumentException {

    public CommandConflictException(String message) {
        super(message);
    }
}
