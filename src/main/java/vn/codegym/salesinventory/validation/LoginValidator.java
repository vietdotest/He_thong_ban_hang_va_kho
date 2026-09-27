package vn.codegym.salesinventory.validation;

import java.util.LinkedHashMap;
import java.util.Map;
import vn.codegym.salesinventory.dto.LoginRequest;

public final class LoginValidator {
    public static final int MAX_IDENTITY_LENGTH = 254;
    public static final int MAX_PASSWORD_LENGTH = 128;

    public Map<String, String> validate(LoginRequest request) {
        Map<String, String> errors = new LinkedHashMap<>();
        String identity = request.identity();
        String password = request.password();

        if (identity == null || identity.trim().isEmpty()) {
            errors.put("identity", "Vui lòng nhập tên đăng nhập hoặc email.");
        } else if (identity.trim().length() > MAX_IDENTITY_LENGTH) {
            errors.put("identity", "Tên đăng nhập hoặc email quá dài.");
        }

        if (password == null || password.isEmpty()) {
            errors.put("password", "Vui lòng nhập mật khẩu.");
        } else if (password.length() > MAX_PASSWORD_LENGTH) {
            errors.put("password", "Mật khẩu vượt quá độ dài cho phép.");
        }
        return errors;
    }
}
