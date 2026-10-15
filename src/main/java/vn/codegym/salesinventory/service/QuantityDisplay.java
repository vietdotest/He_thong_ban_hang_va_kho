package vn.codegym.salesinventory.service;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

/** Decimal presentation never passes through binary floating point. */
public final class QuantityDisplay {
    private QuantityDisplay() { }
    public static String plain(Object value) {
        if (value == null) return "";
        try { return new BigDecimal(value.toString()).stripTrailingZeros().toPlainString(); }
        catch (NumberFormatException e) { return value.toString(); }
    }
    public static String format(Object value) {
        if (value == null) return "—";
        return new DecimalFormat("#,##0.############", DecimalFormatSymbols.getInstance(Locale.forLanguageTag("vi-VN")))
            .format(new BigDecimal(value.toString()));
    }
}
