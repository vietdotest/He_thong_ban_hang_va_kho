package vn.codegym.salesinventory.service;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

/**
 * Dùng chung: Định dạng số lượng bằng số thập phân, tránh sai số do chuyển sang số thực.
 */
public final class QuantityDisplay {

    private QuantityDisplay() {}

    // Định dạng số lượng thành chuỗi thập phân gọn, không dùng ký hiệu số mũ.
    public static String plain(Object value) {
        if (value == null) {
            return "";
        }
        try {
            return new BigDecimal(value.toString()).stripTrailingZeros().toPlainString();
        } catch (NumberFormatException exception) {
            return value.toString();
        }
    }

    // Định dạng giá trị để hiển thị trên giao diện.
    public static String format(Object value) {
        if (value == null) {
            return "—";
        }
        return new DecimalFormat(
                        "#,##0.############",
                        DecimalFormatSymbols.getInstance(Locale.forLanguageTag("vi-VN")))
                .format(new BigDecimal(value.toString()));
    }
}
