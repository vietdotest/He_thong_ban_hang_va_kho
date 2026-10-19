package vn.codegym.salesinventory.validation;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * S2-05 đến S2-10, dùng chung: Kiểm tra dữ liệu danh mục như chuỗi, số thập phân và mã tham chiếu.
 */
public final class CatalogValidation {

    private CatalogValidation() {}

    // Kiểm tra chuỗi bắt buộc và giới hạn độ dài.
    public static String text(String value, int max, String label) {
        if (value == null || value.isBlank() || value.length() > max) {
            throw new IllegalArgumentException(label + " không hợp lệ.");
        }
        return value.trim();
    }

    // Kiểm tra định dạng và độ dài mã danh mục.
    public static String code(String value, int max) {
        if (value == null || !value.matches("[A-Za-z0-9._-]{1," + max + "}")) {
            throw new IllegalArgumentException(
                    "Mã chỉ gồm chữ, số, dấu chấm, gạch dưới hoặc gạch ngang.");
        }
        return value;
    }

    // Kiểm tra số thập phân theo độ chính xác và điều kiện dương được yêu cầu.
    public static BigDecimal decimal(String value, int scale, boolean positive) {
        try {
            if (value == null || value.length() > 128) {
                throw new NumberFormatException();
            }
            return decimal(new BigDecimal(value), scale, positive);
        } catch (NumberFormatException invalid) {
            throw invalidNumber(scale, positive);
        }
    }

    // Kiểm tra số thập phân theo độ chính xác và điều kiện dương được yêu cầu.
    public static BigDecimal decimal(BigDecimal value, int scale, boolean positive) {
        try {
            if (value == null
                    || value.scale() < -19
                    || value.scale() > 128
                    || value.precision() > 128) {
                throw new NumberFormatException();
            }
            BigDecimal normalized = value.setScale(scale, RoundingMode.UNNECESSARY);
            if (normalized.precision() > 19
                    || (positive ? normalized.signum() <= 0 : normalized.signum() < 0)) {
                throw new NumberFormatException();
            }
            return normalized;
        } catch (ArithmeticException | NumberFormatException invalid) {
            throw invalidNumber(scale, positive);
        }
    }

    private static IllegalArgumentException invalidNumber(int scale, boolean positive) {
        return new IllegalArgumentException(
                "Số phải "
                        + (positive ? "lớn hơn 0" : "không âm")
                        + ", tối đa "
                        + scale
                        + " chữ số thập phân.");
    }
}
