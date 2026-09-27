package vn.codegym.salesinventory.validation;

public final class PasswordPolicy {
    public static final int MIN_LENGTH = 8;
    public static final int MAX_LENGTH = 72;

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
