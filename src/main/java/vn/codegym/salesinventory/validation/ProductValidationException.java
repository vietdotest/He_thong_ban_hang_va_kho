package vn.codegym.salesinventory.validation;

import java.util.Map;

public final class ProductValidationException extends IllegalArgumentException {
    private final Map<String, String> errors;
    public ProductValidationException(Map<String, String> errors) {
        super(errors.values().iterator().next());
        this.errors = Map.copyOf(errors);
    }
    public Map<String, String> errors() { return errors; }
    public static ProductValidationException field(String field, String message) {
        return new ProductValidationException(Map.of(field, message));
    }
}
