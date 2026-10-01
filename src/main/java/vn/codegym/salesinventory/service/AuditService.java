package vn.codegym.salesinventory.service;
import java.sql.*;
import java.util.*;
import vn.codegym.salesinventory.dao.Sql;
/** Module tồn kho/công nợ/hóa đơn dùng record trong cùng giao dịch ghi sổ. */
public final class AuditService {
    private static final Set<String> SENSITIVE=Set.of("cost_price","cost","margin","profit_margin");
    private static final Set<String> SECRET=Set.of("password","password_hash","token","token_hash","temporary_password");
    private AuditService() { }
    public static String snapshot(Map<String,?> values,boolean sensitive) {
        if(values==null)return null;StringJoiner json=new StringJoiner(",","{","}");
        values.forEach((key,value) -> { if(!SECRET.contains(key) && SENSITIVE.contains(key)==sensitive)json.add(quote(key)+":"+jsonValue(value)); });return json.toString();
    }
    private static String jsonValue(Object v) { if(v==null)return "null";if(v instanceof Number || v instanceof Boolean)return v.toString();return quote(v.toString()); }
    private static String quote(String value) {
        StringBuilder s=new StringBuilder("\"");for(char ch:value.toCharArray()) {switch(ch) {case '"' -> s.append("\\\"");case '\\' -> s.append("\\\\");case '\n' -> s.append("\\n");case '\r' -> s.append("\\r");case '\t' -> s.append("\\t");default -> {if(ch<32)s.append(String.format("\\u%04x",(int)ch));else s.append(ch);} }}return s.append('"').toString();
    }
    public static void record(Connection c,long actor,String event,String type,long id,Map<String,?> before,Map<String,?> after) throws SQLException {
        Sql.insert(c,"INSERT INTO audit_logs(actor_user_id,event_type,object_type,object_id,before_values,after_values,before_cost,after_cost,occurred_at) VALUES(?,?,?,?,?,?,?,?,UTC_TIMESTAMP(6))",actor,event,type,id,snapshot(before,false),snapshot(after,false),snapshot(before,true),snapshot(after,true));
    }
    public static List<Map<String,Object>> read(Connection c,vn.codegym.salesinventory.security.Access access,String filter,Object[] args,int offset) throws SQLException {
        access.require("AUDIT_READ");
        String sensitive=access.allows("COST_READ") ? ",a.before_cost,a.after_cost" : "";
        var rows=Sql.query(c,"SELECT a.id,a.event_type,a.object_type,a.object_id,a.before_values,a.after_values,a.occurred_at,u.full_name"+sensitive+" FROM audit_logs a LEFT JOIN users u ON u.id=a.actor_user_id"+filter+" ORDER BY a.occurred_at DESC,a.id DESC LIMIT 100 OFFSET "+offset,args);
        for(var row:rows) row.put("display_time",Sql.instant(row.get("occurred_at")).atZone(java.time.ZoneId.of("Asia/Ho_Chi_Minh")).format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss")));
        return rows;
    }
}
