package vn.codegym.salesinventory.validation;

/**
 * S1-03, S1-04: Kiểm tra mật khẩu mới theo quy tắc của ứng dụng.
 */
public final class PasswordPolicy {

    public static final int MIN_LENGTH = 8;

    public static final int MAX_LENGTH = 72;

    // Kiểm tra dữ liệu trước khi tiếp tục xử lý.
    public String validate(String password) {
        if (password == null || password.isBlank()) {
            return "Vui lòng nhập mật khẩu mới.";
        }
        if (password.length() < MIN_LENGTH || password.length() > MAX_LENGTH) {
            return "Mật khẩu phải có từ 8 đến 72 ký tự.";
        }
        if (!password.chars().anyMatch(Character::isLetter)
                || !password.chars().anyMatch(Character::isDigit)) {
            return "Mật khẩu cần có ít nhất một chữ cái và một chữ số.";
        }
        return null;
    }
}
