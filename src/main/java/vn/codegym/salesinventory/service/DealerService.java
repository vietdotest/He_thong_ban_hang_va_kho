package vn.codegym.salesinventory.service;

import java.sql.*;
import java.util.*;
import javax.sql.DataSource;
import vn.codegym.salesinventory.dao.Sql;
import vn.codegym.salesinventory.dto.*;
import vn.codegym.salesinventory.security.Access;
import vn.codegym.salesinventory.validation.*;

/** Dealer reads and writes share the same authorization scope, including counts. */
public final class DealerService {
    private final DataSource source;
    public DealerService(DataSource source) { this.source=source; }
    public record Input(String code,String name,String taxCode,String phone,long group,long territory,
                        long staff,Long warehouse,String status,long version) { }
    public static Map<String,String> errors(Input in) {
        var errors=new LinkedHashMap<String,String>();
        try { CatalogValidation.code(clean(in.code),50); } catch(IllegalArgumentException e) { errors.put("code",e.getMessage()); }
        try { CatalogValidation.text(clean(in.name),200,"Tên đại lý"); } catch(IllegalArgumentException e) { errors.put("name",e.getMessage()); }
        if(!clean(in.taxCode).matches("(?:[0-9]{10}(?:-[0-9]{3})?)?"))errors.put("taxCode","Mã số thuế gồm 10 số, có thể thêm - và 3 số chi nhánh.");
        if(!clean(in.phone).matches("[+0-9() .-]{7,30}"))errors.put("phone","Điện thoại từ 7–30 ký tự, chỉ gồm số và ký tự định dạng.");
        if(in.group<=0)errors.put("group","Hãy chọn nhóm bảng giá.");
        if(in.territory<=0)errors.put("territory","Hãy chọn khu vực.");
        if(in.staff<=0)errors.put("staff","Hãy chọn người phụ trách chính.");
        if(in.warehouse!=null && in.warehouse<=0)errors.put("warehouse","Kho không hợp lệ.");
        if(!Set.of("ACTIVE","DISCONTINUED").contains(clean(in.status)))errors.put("status","Trạng thái hồ sơ không hợp lệ.");
        if(in.version<0)errors.put("form","Phiên bản không hợp lệ.");
        return errors;
    }
    private static String clean(String text){return text==null?"":text.trim();}
    public static Access access(Connection c,long actor,String permission) throws SQLException {
        // The same connection sees permission revocation and writes atomically. Lock actor first.
        var user=Sql.one(c,"SELECT status FROM users WHERE id=? FOR SHARE",actor);
        if(!"ACTIVE".equals(user.get("status")))throw new SecurityException("Tài khoản không còn hoạt động.");
        Set<String> roles=new HashSet<>(),permissions=new HashSet<>();
        for(var row:Sql.query(c,"SELECT r.code FROM roles r JOIN user_roles ur ON ur.role_id=r.id WHERE ur.user_id=? FOR SHARE",actor))roles.add(Sql.text(row.get("code")));
        for(var row:Sql.query(c,"SELECT p.code FROM permissions p JOIN role_permissions rp ON rp.permission_id=p.id JOIN user_roles ur ON ur.role_id=rp.role_id WHERE ur.user_id=? FOR SHARE",actor))permissions.add(Sql.text(row.get("code")));
        var result=new Access(roles,permissions,List.of(),List.of(),List.of());result.require(permission);return result;
    }
    public static boolean all(Access a){return a.allows("DEALER_READ_ALL") && a.roles().stream().anyMatch(Set.of("SALES_MANAGER","ACCOUNTANT","DIRECTOR")::contains);}
    public static Map<String,Object> scoped(Connection c,long actor,Access access,long id,boolean lock) throws SQLException {
        access.require("DEALER_READ");
        var rows=Sql.query(c,"SELECT d.* FROM dealers d WHERE d.id=? AND (? OR d.primary_staff_id=?)"+(lock?" FOR UPDATE":""),id,all(access),actor);
        if(rows.isEmpty())throw new SecurityException("Đại lý không thuộc phạm vi được phép.");
        return rows.get(0);
    }
    private static final String JOIN=" FROM dealers d JOIN customer_groups g ON g.id=d.group_id JOIN territories t ON t.id=d.territory_id JOIN users u ON u.id=d.primary_staff_id LEFT JOIN warehouses w ON w.id=d.warehouse_id";
    private static final String COLUMNS="d.*,g.name group_name,t.name territory_name,u.full_name staff_name,w.name warehouse_name";
    public PageResult<Map<String,Object>> search(long actor,String query,PageRequest requested) {
        String q=clean(query);if(q.length()>150)q=q.substring(0,150);String pattern="%"+q+"%";
        return Sql.transaction(source,c->{var a=access(c,actor,"DEALER_READ");
            String filter=" WHERE (? OR d.primary_staff_id=?) AND (d.code LIKE ? OR d.name LIKE ? OR d.tax_code LIKE ? OR d.phone LIKE ?)";
            Object[] args={all(a),actor,pattern,pattern,pattern,pattern};
            long count=Sql.id(Sql.one(c,"SELECT COUNT(*) total FROM dealers d"+filter,args).get("total"));var paging=requested.clamp(count);
            var params=new ArrayList<Object>(Arrays.asList(args));params.add(paging.pageSize());params.add(paging.offset());
            var rows=Sql.query(c,"SELECT "+COLUMNS+JOIN+filter+" ORDER BY d.code,d.id LIMIT ? OFFSET ?",params.toArray());
            return new PageResult<>(rows,count,paging.page(),paging.pageSize());
        });
    }
    public Map<String,Object> find(long actor,long id){return Sql.transaction(source,c->{var a=access(c,actor,"DEALER_READ");scoped(c,actor,a,id,false);return Sql.one(c,"SELECT "+COLUMNS+JOIN+" WHERE d.id=?",id);});}
    public Map<String,List<Map<String,Object>>> options(long actor){return Sql.transaction(source,c->{access(c,actor,"DEALER_MANAGE");
        return Map.of("groups",Sql.query(c,"SELECT id,code,name FROM customer_groups ORDER BY name,id"),
            "territories",Sql.query(c,"SELECT id,code,name,address FROM territories ORDER BY name,id"),
            "staff",Sql.query(c,"SELECT DISTINCT u.id,u.username code,u.full_name name FROM users u JOIN user_roles ur ON ur.user_id=u.id JOIN roles r ON r.id=ur.role_id WHERE u.status='ACTIVE' AND r.code IN ('SALES','SALES_MANAGER') ORDER BY u.full_name,u.id"),
            "warehouses",Sql.query(c,"SELECT id,code,name,address FROM warehouses ORDER BY name,id"));});}
    public long save(long actor,long id,Input in) {
        var errors=errors(in);if(!errors.isEmpty())throw new FieldValidationException(errors);
        return Sql.transaction(source,c->{var a=access(c,actor,"DEALER_MANAGE");
            if(!a.roles().stream().anyMatch(Set.of("SALES_MANAGER","ACCOUNTANT")::contains))throw new SecurityException("Chỉ Quản lý kinh doanh hoặc Kế toán được sửa hồ sơ.");
            Map<String,Object> before=id==0?null:scoped(c,actor,a,id,true);
            if(before!=null && Sql.id(before.get("version"))!=in.version)throw FieldValidationException.field("form","Hồ sơ đã được người khác sửa. Hãy tải lại.");
            if(!Sql.query(c,"SELECT id FROM dealers WHERE code=? AND id<>?",clean(in.code),id).isEmpty())throw FieldValidationException.field("code","Mã đại lý đã tồn tại.");
            for(var check:List.of(new Object[]{"customer_groups",in.group,"group"},new Object[]{"territories",in.territory,"territory"}))
                if(Sql.query(c,"SELECT id FROM "+check[0]+" WHERE id=? FOR SHARE",check[1]).isEmpty())throw FieldValidationException.field(check[2].toString(),"Lựa chọn không còn tồn tại.");
            if(Sql.query(c,"SELECT u.id FROM users u JOIN user_roles ur ON ur.user_id=u.id JOIN roles r ON r.id=ur.role_id WHERE u.id=? AND u.status='ACTIVE' AND r.code IN ('SALES','SALES_MANAGER') FOR SHARE",in.staff).isEmpty())throw FieldValidationException.field("staff","Người phụ trách phải là nhân viên kinh doanh đang hoạt động.");
            if(in.warehouse!=null && Sql.query(c,"SELECT id FROM warehouses WHERE id=? FOR SHARE",in.warehouse).isEmpty())throw FieldValidationException.field("warehouse","Kho không còn tồn tại.");
            // Handover changes get a dedicated audited operation in SCRUM-42, not a silent profile edit.
            if(before!=null && Sql.id(before.get("primary_staff_id"))!=in.staff)throw FieldValidationException.field("staff","Dùng chức năng bàn giao để đổi người phụ trách.");
            Object[] values={clean(in.code),clean(in.name),clean(in.taxCode),clean(in.phone),in.group,in.territory,in.staff,in.warehouse,in.status};
            long saved=id;
            try {if(id==0)saved=Sql.insert(c,"INSERT INTO dealers(code,name,tax_code,phone,group_id,territory_id,primary_staff_id,warehouse_id,status) VALUES(?,?,?,?,?,?,?,?,?)",values);
                else {var params=new ArrayList<Object>(Arrays.asList(values));params.add(id);Sql.update(c,"UPDATE dealers SET code=?,name=?,tax_code=?,phone=?,group_id=?,territory_id=?,primary_staff_id=?,warehouse_id=?,status=?,version=version+1 WHERE id=?",params.toArray());}}
            catch(SQLException e){if(e.getErrorCode()==1062)throw FieldValidationException.field("code","Mã đại lý đã tồn tại.");throw e;}
            syncReference(c,saved,in.staff);
            AuditService.record(c,actor,"DEALER_SAVED","DEALER",saved,before,Sql.one(c,"SELECT * FROM dealers WHERE id=?",saved));return saved;
        });
    }
    public static void syncReference(Connection c,long dealer,long staff) throws SQLException {
        String ref="DEALER:"+dealer;Sql.update(c,"DELETE FROM dealer_staff_references WHERE dealer_reference=?",ref);
        Sql.update(c,"INSERT INTO dealer_staff_references(user_id,dealer_reference) VALUES(?,?)",staff,ref);
    }
    public void delete(long actor,long id,long version){Sql.transaction(source,c->{var a=access(c,actor,"DEALER_MANAGE");var before=scoped(c,actor,a,id,true);
        if(!a.roles().stream().anyMatch(Set.of("SALES_MANAGER","ACCOUNTANT")::contains))throw new SecurityException("Không có quyền xóa hồ sơ.");
        if(Sql.id(before.get("version"))!=version)throw new IllegalArgumentException("Hồ sơ đã thay đổi, hãy tải lại.");
        if(!Sql.query(c,"SELECT dealer_id FROM dealer_transaction_references WHERE dealer_id=? LIMIT 1",id).isEmpty())throw new IllegalArgumentException("Đại lý đã có giao dịch. Chỉ được ngừng giao dịch.");
        Sql.update(c,"DELETE FROM dealer_staff_references WHERE dealer_reference=?","DEALER:"+id);
        Sql.update(c,"DELETE FROM dealers WHERE id=?",id);AuditService.record(c,actor,"DEALER_DELETED","DEALER",id,before,null);return null;
    });}
}
