package vn.codegym.salesinventory.validation;
import java.math.BigDecimal;
import java.math.RoundingMode;
public final class CatalogValidation {
    private CatalogValidation() { }
    public static String text(String value,int max,String label) {
        if(value==null||value.isBlank()||value.length()>max)throw new IllegalArgumentException(label+" không hợp lệ.");
        return value.trim();
    }
    public static String code(String value,int max) {
        if(value==null||!value.matches("[A-Za-z0-9._-]{1,"+max+"}"))throw new IllegalArgumentException("Mã chỉ gồm chữ, số, dấu chấm, gạch dưới hoặc gạch ngang.");
        return value;
    }
    public static BigDecimal decimal(String value,int scale,boolean positive) {
        try {
            if(value==null||value.length()>128)throw new NumberFormatException();
            return decimal(new BigDecimal(value),scale,positive);
        } catch(NumberFormatException invalid) {throw invalidNumber(scale,positive);}
    }
    public static BigDecimal decimal(BigDecimal value,int scale,boolean positive) {
        try {
            if(value==null||value.scale() < -19||value.scale()>128||value.precision()>128)throw new NumberFormatException();
            BigDecimal normalized=value.setScale(scale,RoundingMode.UNNECESSARY);
            if(normalized.precision()>19||(positive?normalized.signum()<=0:normalized.signum()<0))throw new NumberFormatException();
            return normalized;
        } catch(ArithmeticException|NumberFormatException invalid) {throw invalidNumber(scale,positive);}
    }
    private static IllegalArgumentException invalidNumber(int scale,boolean positive) {
        return new IllegalArgumentException("Số phải "+(positive?"lớn hơn 0":"không âm")+", tối đa "+scale+" chữ số thập phân.");
    }
}
