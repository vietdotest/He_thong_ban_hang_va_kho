package vn.codegym.salesinventory.service;
import java.sql.*;
import java.util.*;
import vn.codegym.salesinventory.dao.Sql;
/** Module tồn kho/công nợ/hóa đơn dùng record trong cùng giao dịch ghi sổ. */
public final class AuditService {
    private static final Set<String> SENSITIVE=Set.of("cost_price","cost","margin","profit_margin");
    private static final Set<String> SECRET=Set.of("password","password_hash","token","token_hash","temporary_password","password_confirmation","csrf_token","_csrf","reset_token","activation_token");
    private AuditService() { }
    public static String eventLabel(String code) {
        return switch(code) {
            case "LOGIN_SUCCESS" -> "Đăng nhập";case "LOGIN_FAILURE" -> "Đăng nhập không thành công";
            case "SESSION_CREATED" -> "Mở phiên làm việc";case "LOGOUT" -> "Đăng xuất";
            case "PROFILE_UPDATED" -> "Cập nhật hồ sơ";case "AVATAR_UPDATED" -> "Cập nhật ảnh đại diện";
            case "USER_CREATED" -> "Tạo tài khoản";case "USER_UPDATED" -> "Cập nhật tài khoản";
            case "USER_LOCKED","ACCOUNT_LOCKED" -> "Khóa tài khoản";case "USER_UNLOCKED","ACCOUNT_UNLOCKED" -> "Mở khóa tài khoản";
            case "USER_ASSIGNMENTS_UPDATED" -> "Cập nhật phân công";case "ROLE_PERMISSIONS_UPDATED" -> "Cập nhật phân quyền";case "SCOPE_UPDATED" -> "Cập nhật kho / địa bàn";
            case "PRODUCT_SAVED","PRODUCT_UPDATED" -> "Lưu sản phẩm";case "PRODUCT_CREATED" -> "Thêm sản phẩm";case "PRODUCT_DELETED" -> "Xóa sản phẩm";
            case "CATEGORY_SAVED" -> "Lưu nhóm hàng";case "CATEGORY_DELETED" -> "Xóa nhóm hàng";
            case "DEALER_SAVED" -> "Lưu hồ sơ đại lý";case "DEALER_DELETED" -> "Xóa hồ sơ đại lý";
            case "UNIT_SAVED" -> "Lưu đơn vị quy đổi";case "UNIT_DELETED" -> "Xóa đơn vị quy đổi";
            case "SUPPLIER_SAVED" -> "Lưu nhà cung cấp";case "SUPPLIER_DELETED" -> "Xóa nhà cung cấp";case "SCOPE_CREATED" -> "Thêm kho / địa bàn";
            case "PRICE_VERSION_CREATED" -> "Tạo phiên bản bảng giá";case "PRICE_VERSION_UPDATED" -> "Sửa hiệu lực bảng giá";
            case "PRICE_ITEM_SAVED","PRICE_ITEM_UPDATED" -> "Cập nhật giá bán";case "PRICE_ITEM_DELETED" -> "Xóa giá sản phẩm";
            default -> code;
        };
    }
    public static String objectLabel(String code) {
        return switch(code) {
            case "USER" -> "Tài khoản";case "PRODUCT" -> "Sản phẩm";case "PRICE_VERSION" -> "Bảng giá";case "PRICE_ITEM" -> "Giá sản phẩm";
            case "DEALER" -> "Đại lý";
            case "CATEGORY" -> "Nhóm hàng";case "SUPPLIER" -> "Nhà cung cấp";case "UNIT","PRODUCT_UNIT" -> "Đơn vị quy đổi";
            case "WAREHOUSE" -> "Kho";case "TERRITORY" -> "Địa bàn";case "ROLE" -> "Vai trò";case "SESSION","" -> "Phiên làm việc";
            default -> code;
        };
    }
    public static String snapshot(Map<String,?> values,boolean sensitive) {
        if(values==null)return null;StringJoiner json=new StringJoiner(",","{","}");
        values.forEach((key,value) -> { String normalized=key.toLowerCase(Locale.ROOT);if(!SECRET.contains(normalized) && SENSITIVE.contains(normalized)==sensitive)json.add(quote(key)+":"+jsonValue(value,sensitive)); });return json.toString();
    }
    private static String jsonValue(Object v,boolean sensitive) {
        if(v==null)return "null";if(v instanceof Number || v instanceof Boolean)return v.toString();
        if(v instanceof Map<?,?> map) {var values=new LinkedHashMap<String,Object>();map.forEach((k,value)->values.put(String.valueOf(k),value));return snapshot(values,sensitive);}
        if(v instanceof Collection<?> items) {StringJoiner json=new StringJoiner(",","[","]");items.forEach(item->json.add(jsonValue(item,sensitive)));return json.toString();}
        return quote(v.toString());
    }
    private static String quote(String value) {
        StringBuilder s=new StringBuilder("\"");for(char ch:value.toCharArray()) {switch(ch) {case '"' -> s.append("\\\"");case '\\' -> s.append("\\\\");case '\n' -> s.append("\\n");case '\r' -> s.append("\\r");case '\t' -> s.append("\\t");default -> {if(ch<32)s.append(String.format("\\u%04x",(int)ch));else s.append(ch);} }}return s.append('"').toString();
    }
    public static void record(Connection c,long actor,String event,String type,long id,Map<String,?> before,Map<String,?> after) throws SQLException {
        Sql.insert(c,"INSERT INTO audit_logs(actor_user_id,event_type,object_type,object_id,before_values,after_values,before_cost,after_cost,occurred_at) VALUES(?,?,?,?,?,?,?,?,CURRENT_TIMESTAMP(6))",actor,event,type,id,snapshot(before,false),snapshot(after,false),snapshot(before,true),snapshot(after,true));
    }
    /** Overview intentionally excludes snapshots and all sensitive values. */
    public static List<Map<String,Object>> recent(Connection c,vn.codegym.salesinventory.security.Access access) throws SQLException {
        access.require("AUDIT_READ");
        var rows=Sql.query(c,"SELECT a.event_type,a.object_type,a.object_id,a.occurred_at,u.full_name FROM audit_logs a LEFT JOIN users u ON u.id=a.actor_user_id ORDER BY a.occurred_at DESC,a.id DESC LIMIT 5");
        for(var row:rows) {
            row.put("display_time",Sql.instant(row.get("occurred_at")).atZone(java.time.ZoneId.of("Asia/Ho_Chi_Minh")).format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")));
            row.put("event_label",eventLabel(Sql.text(row.get("event_type"))));
            row.put("object_label",objectLabel(Sql.text(row.get("object_type"))));
        }
        return rows;
    }
    public static List<Map<String,Object>> read(Connection c,vn.codegym.salesinventory.security.Access access,String filter,Object[] args,int offset) throws SQLException {
        access.require("AUDIT_READ");
        if(offset<0)throw new IllegalArgumentException("Trang không hợp lệ.");
        String sensitive=access.allows("COST_READ") ? ",a.before_cost,a.after_cost" : "";
        var rows=Sql.query(c,"SELECT a.id,a.event_type,a.object_type,a.object_id,a.before_values,a.after_values,a.occurred_at,u.full_name"+sensitive+" FROM audit_logs a LEFT JOIN users u ON u.id=a.actor_user_id"+filter+" ORDER BY a.occurred_at DESC,a.id DESC LIMIT 100 OFFSET "+offset,args);
        for(var row:rows) {
            row.put("display_time",Sql.instant(row.get("occurred_at")).atZone(java.time.ZoneId.of("Asia/Ho_Chi_Minh")).format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss")));
            row.put("event_label",eventLabel(Sql.text(row.get("event_type"))));row.put("object_label",objectLabel(Sql.text(row.get("object_type"))));
        }
        return rows;
    }
}
