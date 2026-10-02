package vn.codegym.salesinventory.validation;

import java.util.Map;

public final class ProfileValidationException extends IllegalArgumentException {
    private final Map<String, String> errors;

    public ProfileValidationException(Map<String, String> errors) {
        super("Thông tin hồ sơ chưa hợp lệ.");
        this.errors = Map.copyOf(errors);
    }

    public Map<String, String> errors() {
        return errors;
    }
}
