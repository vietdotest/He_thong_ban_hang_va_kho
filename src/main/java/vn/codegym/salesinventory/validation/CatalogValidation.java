package vn.codegym.salesinventory.validation;
import java.math.BigDecimal;
public final class CatalogValidation {
 private CatalogValidation(){}
 public static String text(String value,int max,String label){if(value==null||value.isBlank()||value.length()>max)throw new IllegalArgumentException(label+" không hợp lệ.");return value.trim();}
 public static String code(String value,int max){if(value==null||!value.matches("[A-Za-z0-9._-]{1,"+max+"}"))throw new IllegalArgumentException("Mã chỉ gồm chữ, số, dấu chấm, gạch dưới hoặc gạch ngang.");return value;}
 public static BigDecimal decimal(String value,int scale,boolean positive){try{BigDecimal n=new BigDecimal(value).setScale(scale,java.math.RoundingMode.UNNECESSARY);if(n.precision()>19||(positive?n.signum()<=0:n.signum()<0))throw new NumberFormatException();return n;}catch(Exception e){throw new IllegalArgumentException("Số phải "+(positive?"lớn hơn 0":"không âm")+", tối đa "+scale+" chữ số thập phân.");}}
}
