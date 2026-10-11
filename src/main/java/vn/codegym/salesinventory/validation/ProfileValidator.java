package vn.codegym.salesinventory.validation;

import java.util.LinkedHashMap;
import java.util.Map;

public final class ProfileValidator {
    private ProfileValidator() { }

    public static Map<String, String> validate(String name, String phone) {
        Map<String, String> errors = new LinkedHashMap<>();
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.length() < 2 || trimmed.length() > 150) {
            errors.put("fullName", "Họ tên phải có từ 2 đến 150 ký tự.");
        }
        try {
            PhoneNumber.normalize(phone);
        } catch (IllegalArgumentException exception) {
            errors.put("phone", exception.getMessage());
        }
        return errors;
    }
}
