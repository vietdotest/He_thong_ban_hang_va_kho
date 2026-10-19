package vn.codegym.salesinventory.service;

import java.math.*;

/**
 * Dùng chung: Định dạng tiền để hiển thị, không thay đổi giá trị dùng để tính toán.
 */
public final class MoneyDisplay {

    private MoneyDisplay() {}

    // Định dạng giá trị để hiển thị trên giao diện.
    public static String format(Object value) {
        if (value == null) {
            return "—";
        }
        BigDecimal number = value instanceof BigDecimal d ? d : new BigDecimal(value.toString());
        return number.setScale(0, RoundingMode.HALF_UP)
                .toPlainString()
                .replaceAll("\\B(?=(\\d{3})+(?!\\d))", ".");
    }
}
