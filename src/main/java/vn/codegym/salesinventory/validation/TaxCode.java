package vn.codegym.salesinventory.validation;

/**
 * S2-09, S3-03: Chuẩn hóa và kiểm tra mã số thuế.
 */
public final class TaxCode {

    private TaxCode() {}

    // Chuẩn hóa dữ liệu đầu vào trước khi kiểm tra hoặc lưu.
    public static String normalize(String value) {
        String code = value == null ? "" : value.trim();
        if (code.isEmpty()) {
            return "";
        }
        if (!code.matches("[0-9]{10}(?:-?[0-9]{3})?")) {
            throw new IllegalArgumentException("Mã số thuế gồm 10 số hoặc 13 số chi nhánh.");
        }
        String digits = code.replace("-", "");
        return digits.length() == 13
                ? digits.substring(0, 10) + "-" + digits.substring(10)
                : digits;
    }
}
