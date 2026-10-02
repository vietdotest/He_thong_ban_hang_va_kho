package vn.codegym.salesinventory.validation;

import java.util.Map;

public final class FieldValidationException extends IllegalArgumentException {
    private final Map<String,String> errors;
    public FieldValidationException(Map<String,String> errors) {
        super(errors.values().stream().findFirst().orElse("Dữ liệu không hợp lệ."));this.errors=Map.copyOf(errors);
    }
    public Map<String,String> errors() { return errors; }
    public static FieldValidationException field(String field,String message) { return new FieldValidationException(Map.of(field,message)); }
}
