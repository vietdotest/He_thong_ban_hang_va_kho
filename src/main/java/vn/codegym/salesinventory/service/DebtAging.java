package vn.codegym.salesinventory.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;

public final class DebtAging {
    private DebtAging() { }
    public static final Map<String,String> LABELS;
    static {
        var labels=new LinkedHashMap<String,String>();labels.put("not_due","Chưa đến hạn");labels.put("due_today","Đến hạn hôm nay");
        labels.put("days_1_30","Quá hạn 1–30 ngày");labels.put("days_31_60","Quá hạn 31–60 ngày");
        labels.put("days_61_90","Quá hạn 61–90 ngày");labels.put("days_over_90","Quá hạn trên 90 ngày");LABELS=Collections.unmodifiableMap(labels);
    }
    public static String bucket(LocalDate due,LocalDate today) {
        long days=ChronoUnit.DAYS.between(due,today);
        return days<0?"not_due":days==0?"due_today":days<=30?"days_1_30":days<=60?"days_31_60":days<=90?"days_61_90":"days_over_90";
    }
    public static Map<String,BigDecimal> empty() {
        var amounts=new LinkedHashMap<String,BigDecimal>();for(String key:LABELS.keySet())amounts.put(key,BigDecimal.ZERO);return amounts;
    }
}
