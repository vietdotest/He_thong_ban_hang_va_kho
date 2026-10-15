package vn.codegym.salesinventory.service;
import java.math.*;
/** Presentation only: calculation and persisted decimal amounts remain unchanged. */
public final class MoneyDisplay {
    private MoneyDisplay() { }
    public static String format(Object value) {
        if(value==null)return "—";
        BigDecimal number=value instanceof BigDecimal d?d:new BigDecimal(value.toString());
        return number.setScale(0,RoundingMode.HALF_UP).toPlainString().replaceAll("\\B(?=(\\d{3})+(?!\\d))",".");
    }
}
