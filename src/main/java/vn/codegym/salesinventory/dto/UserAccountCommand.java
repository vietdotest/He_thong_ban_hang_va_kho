package vn.codegym.salesinventory.dto;

import java.util.Locale;
import vn.codegym.salesinventory.model.UserStatus;

/**
 * S1-08: Mang dữ liệu đầu vào khi tạo hoặc sửa tài khoản.
 */
public record UserAccountCommand(
        String username,
        String email,
        String fullName,
        String phone,
        String roleCode,
        UserStatus status,
        long version) {

    public UserAccountCommand {
        username = trim(username);
        email = trim(email);
        fullName = trim(fullName);
        phone = trim(phone);
        roleCode = trim(roleCode).toUpperCase(Locale.ROOT);
        status = status == null ? UserStatus.ACTIVE : status;
    }

    public String normalizedUsername() {
        return username.toLowerCase(Locale.ROOT);
    }

    public String normalizedEmail() {
        return email.toLowerCase(Locale.ROOT);
    }

    // Chuẩn hóa số điện thoại trước khi kiểm tra hoặc lưu tài khoản.
    public String normalizedPhone() {
        String digits = phone.replaceAll("[^0-9+]", "");
        return digits.startsWith("+84") ? "0" + digits.substring(3) : digits;
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }
}
