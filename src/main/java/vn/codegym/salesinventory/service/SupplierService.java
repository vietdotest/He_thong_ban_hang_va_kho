package vn.codegym.salesinventory.service;

import java.util.*;
import java.sql.*;
import javax.sql.DataSource;
import vn.codegym.salesinventory.dao.Sql;
import vn.codegym.salesinventory.dto.*;
import vn.codegym.salesinventory.security.Access;
import vn.codegym.salesinventory.validation.*;

public final class SupplierService {
    private final DataSource source;
    private static final String FIELDS="id,code,name,tax_code,contact_name,contact_phone,payment_terms,warehouse_id,status,version";
    public SupplierService(DataSource source){this.source=source;}
    public record Input(String code,String name,String taxCode,String contact,String phone,String terms,long warehouse,String status,long version) {}
    public List<Map<String,Object>> list(long actor,String query){
        return search(actor,query,new PageRequest(1,100)).items();
    }
    public PageResult<Map<String,Object>> search(long actor,String query,PageRequest requested){
        String q=term(query),pattern="%"+q+"%";
        return Sql.snapshot(source,c->{var a=access(c,actor,"CATALOG_READ");
            String condition=" WHERE (s.code LIKE ? OR s.name LIKE ?) AND (? OR EXISTS(SELECT 1 FROM user_warehouses uw WHERE uw.user_id=? AND uw.warehouse_id=s.warehouse_id))";
            var args=new ArrayList<Object>(List.of(pattern,pattern,!a.warehouseScoped(),actor));
            long total=Sql.id(Sql.one(c,"SELECT COUNT(*) total FROM suppliers s JOIN warehouses w ON w.id=s.warehouse_id"+condition,args.toArray()).get("total"));var page=requested.clamp(total);args.add(page.pageSize());args.add(page.offset());
            var rows=Sql.query(c,"SELECT s.*,w.name warehouse_name FROM suppliers s JOIN warehouses w ON w.id=s.warehouse_id"+condition+" ORDER BY s.name,s.id LIMIT ? OFFSET ?",args.toArray());
            return new PageResult<>(rows,total,page.page(),page.pageSize());});
    }
    public List<Map<String,Object>> suggest(long actor,String query){String q=term(query);return Sql.snapshot(source,c->{var a=access(c,actor,"CATALOG_READ");if(q.length()<2)return List.of();
        return Sql.query(c,"SELECT s.id,s.code,s.name,w.name detail FROM suppliers s JOIN warehouses w ON w.id=s.warehouse_id WHERE (s.code LIKE ? OR s.name LIKE ?) AND (? OR EXISTS(SELECT 1 FROM user_warehouses uw WHERE uw.user_id=? AND uw.warehouse_id=s.warehouse_id)) ORDER BY (s.code=?) DESC,(s.code LIKE ?) DESC,s.name,s.id LIMIT 10","%"+q+"%","%"+q+"%",!a.warehouseScoped(),actor,q,q+"%");});}
    private static String term(String query){String q=query==null?"":query.trim();return q.length()>150?q.substring(0,150):q;}
    private static Access access(Connection c,long actor,String permission)throws SQLException{
        var a=DealerService.access(c,actor,permission);
        return new Access(a.roles(),a.permissions(),a.roleNames(),Sql.query(c,"SELECT w.id,w.code,w.name,w.address FROM warehouses w JOIN user_warehouses uw ON uw.warehouse_id=w.id WHERE uw.user_id=? ORDER BY w.name,w.id FOR SHARE",actor),List.of());
    }
    public Map<String,Object> find(long actor,long id){
        return Sql.transaction(source,c->{var a=access(c,actor,"CATALOG_READ");var row=Sql.one(c,"SELECT "+FIELDS+" FROM suppliers WHERE id=?",id);
            if(a.warehouseScoped()&&!a.managesWarehouse(Sql.id(row.get("warehouse_id"))))throw new SecurityException();return row;});
    }
    public static Map<String,String> errors(Input in){
        var errors=new LinkedHashMap<String,String>();
        check(errors,"code",()->CatalogValidation.code(in.code==null?null:in.code.trim(),50));
        check(errors,"name",()->CatalogValidation.text(in.name,200,"Tên nhà cung cấp"));
        check(errors,"contact",()->CatalogValidation.text(in.contact,150,"Người liên hệ"));
        check(errors,"terms",()->CatalogValidation.text(in.terms,250,"Điều khoản thanh toán"));
        check(errors,"phone",()->PhoneNumber.normalize(in.phone));
        if(in.taxCode==null||!in.taxCode.trim().matches("[0-9]{10}(-?[0-9]{3})?"))errors.put("taxCode","Mã số thuế gồm 10 hoặc 13 chữ số.");
        if(in.status==null||!Set.of("ACTIVE","DISCONTINUED").contains(in.status))errors.put("status","Trạng thái không hợp lệ.");
        if(in.warehouse<=0)errors.put("warehouse","Kho không hợp lệ.");
        if(in.version<0)errors.put("form","Phiên bản không hợp lệ.");
        return errors;
    }
    private static void check(Map<String,String> errors,String field,Runnable check){try{check.run();}catch(IllegalArgumentException invalid){errors.put(field,invalid.getMessage());}}
    public long save(long actor,long id,Input in){
        var errors=errors(in);if(id<0)errors.put("form","Mã bản ghi không hợp lệ.");if(!errors.isEmpty())throw new FieldValidationException(errors);
        String code=in.code.trim(),name=in.name.trim(),tax=in.taxCode.trim(),contact=in.contact.trim(),terms=in.terms.trim(),phone=PhoneNumber.normalize(in.phone);
        return Sql.transaction(source,c->{
            var a=access(c,actor,"WAREHOUSE_MANAGE");if(!a.managesWarehouse(in.warehouse))throw new SecurityException();
            Map<String,Object> before=null;
            if(id!=0){
                before=Sql.one(c,"SELECT "+FIELDS+" FROM suppliers WHERE id=? FOR UPDATE",id);
                if(!a.managesWarehouse(Sql.id(before.get("warehouse_id"))))throw new SecurityException();
                if(Sql.id(before.get("version"))!=in.version)throw FieldValidationException.field("form","Nhà cung cấp đã thay đổi. Hãy tải lại.");
            }
            if(Sql.query(c,"SELECT id FROM warehouses WHERE id=? FOR SHARE",in.warehouse).isEmpty())throw FieldValidationException.field("warehouse","Kho không còn tồn tại.");
            if(!Sql.query(c,"SELECT id FROM suppliers WHERE code=? AND id<>?",code,id).isEmpty())throw FieldValidationException.field("code","Mã nhà cung cấp đã tồn tại.");
            long saved=id;
            try{
                if(id==0)saved=Sql.insert(c,"INSERT INTO suppliers(code,name,tax_code,contact_name,contact_phone,payment_terms,warehouse_id,status) VALUES(?,?,?,?,?,?,?,?)",code,name,tax,contact,phone,terms,in.warehouse,in.status);
                else Sql.update(c,"UPDATE suppliers SET code=?,name=?,tax_code=?,contact_name=?,contact_phone=?,payment_terms=?,warehouse_id=?,status=?,version=version+1 WHERE id=?",code,name,tax,contact,phone,terms,in.warehouse,in.status,id);
            }catch(java.sql.SQLException failure){
                String message=String.valueOf(failure.getMessage()).toLowerCase(Locale.ROOT);
                if(failure.getErrorCode()==1062&&"23000".equals(failure.getSQLState())&&(message.contains("'code'")||message.contains("'suppliers.code'")))throw FieldValidationException.field("code","Mã nhà cung cấp đã tồn tại.");
                throw failure;
            }
            AuditService.record(c,actor,"SUPPLIER_SAVED","SUPPLIER",saved,before,Sql.one(c,"SELECT "+FIELDS+" FROM suppliers WHERE id=?",saved));return saved;
        });
    }
    public void delete(long actor,long id){
        Sql.transaction(source,c->{
            var a=access(c,actor,"WAREHOUSE_MANAGE");
            var before=Sql.one(c,"SELECT "+FIELDS+" FROM suppliers WHERE id=? FOR UPDATE",id);
            if(!a.managesWarehouse(Sql.id(before.get("warehouse_id"))))throw new SecurityException();
            if(!Sql.query(c,"SELECT supplier_id FROM supplier_receipt_references WHERE supplier_id=? LIMIT 1",id).isEmpty())throw FieldValidationException.field("form","Nhà cung cấp đã có phiếu nhập. Hãy ngừng giao dịch.");
            Sql.update(c,"DELETE FROM suppliers WHERE id=?",id);AuditService.record(c,actor,"SUPPLIER_DELETED","SUPPLIER",id,before,null);return null;
        });
    }
}
