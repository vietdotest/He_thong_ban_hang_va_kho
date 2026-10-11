package vn.codegym.salesinventory.service;
import java.math.BigDecimal;
import java.sql.*;
import java.util.*;
import javax.sql.DataSource;
import vn.codegym.salesinventory.dao.Sql;
import vn.codegym.salesinventory.validation.*;
import vn.codegym.salesinventory.security.Access;
import vn.codegym.salesinventory.dto.*;

public final class UnitService {
    private final DataSource source;
    public UnitService(DataSource source) {this.source=source;}
    public record Conversion(long productId,long unitId,String unitName,BigDecimal quantity,BigDecimal factor,long version,BigDecimal baseQuantity,Long warehouseId) {
        public Conversion(long productId,long unitId,String unitName,BigDecimal quantity,BigDecimal factor,long version,BigDecimal baseQuantity) {
            this(productId,unitId,unitName,quantity,factor,version,baseQuantity,null);
        }
    }
    public static Map<String,String> errors(String name,BigDecimal factor,long product,long version) {
        var errors=new LinkedHashMap<String,String>();
        try {CatalogValidation.text(name==null?null:name.trim(),50,"Đơn vị");}catch(IllegalArgumentException invalid){errors.put("name",invalid.getMessage());}
        try {CatalogValidation.decimal(factor,6,true);}catch(IllegalArgumentException invalid){errors.put("factor",invalid.getMessage());}
        if(product<=0||version<0)errors.put("form","Sản phẩm hoặc phiên bản không hợp lệ.");
        return errors;
    }
    public List<Map<String,Object>> list(long actor,long product) {
        return search(actor,product,"",new PageRequest(1,100)).items();
    }
    public PageResult<Map<String,Object>> search(long actor,long product,String query,PageRequest requested) {
        String q=query==null?"":query.trim();if(q.length()>150)q=q.substring(0,150);String pattern="%"+q+"%";
        return Sql.snapshot(source,c->{var access=access(c,actor,"CATALOG_READ");
            String condition=" WHERE u.product_id=? AND (u.name LIKE ? OR w.name LIKE ? OR w.code LIKE ?) AND (? OR u.warehouse_id IS NULL OR EXISTS(SELECT 1 FROM user_warehouses uw WHERE uw.user_id=? AND uw.warehouse_id=u.warehouse_id))";
            var args=new ArrayList<Object>(List.of(product,pattern,pattern,pattern,!access.warehouseScoped(),actor));
            long total=Sql.id(Sql.one(c,"SELECT COUNT(*) total FROM product_units u LEFT JOIN warehouses w ON w.id=u.warehouse_id"+condition,args.toArray()).get("total"));var page=requested.clamp(total);args.add(page.pageSize());args.add(page.offset());
            var rows=Sql.query(c,"SELECT u.id,u.product_id,u.name,u.factor,u.version,u.is_base,u.warehouse_id,w.name warehouse_name FROM product_units u LEFT JOIN warehouses w ON w.id=u.warehouse_id"+condition+" ORDER BY u.is_base DESC,u.name,u.id LIMIT ? OFFSET ?",args.toArray());
            return new PageResult<>(rows,total,page.page(),page.pageSize());});
    }
    private static Access access(Connection c,long actor,String permission)throws SQLException {
        var a=DealerService.access(c,actor,permission);
        if(PortalAccountService.portal(c,actor))throw new SecurityException();
        return new Access(a.roles(),a.permissions(),a.roleNames(),Sql.query(c,"SELECT w.id,w.code,w.name,w.address FROM warehouses w JOIN user_warehouses uw ON uw.warehouse_id=w.id WHERE uw.user_id=? ORDER BY w.name,w.id FOR SHARE",actor),List.of());
    }
    /** The editor must not reuse USER_MANAGE or expose unassigned warehouses. */
    public List<Map<String,Object>> suggestWarehouses(long actor,String query) {
        String q=query==null?"":query.trim();if(q.length()>150)q=q.substring(0,150);
        String exact=q,prefix=q+"%",contains="%"+q+"%";
        return Sql.snapshot(source,c->{access(c,actor,"WAREHOUSE_MANAGE");if(exact.length()<2)return List.of();
            return Sql.query(c,"SELECT w.id,w.code,w.name,w.address detail FROM warehouses w JOIN user_warehouses uw ON uw.warehouse_id=w.id WHERE uw.user_id=? AND (w.code LIKE ? OR w.name LIKE ? OR w.address LIKE ?) ORDER BY (w.code=?) DESC,(w.code LIKE ?) DESC,w.name,w.id LIMIT 10",actor,contains,contains,contains,exact,prefix);});
    }
    public List<Map<String,Object>> suggestNames(long actor,long product,String query) {
        String q=query==null?"":query.trim();if(q.length()>150)q=q.substring(0,150);
        String exact=q,prefix=q+"%",contains="%"+q+"%";
        return Sql.snapshot(source,c->{var a=access(c,actor,"CATALOG_READ");if(product<=0)throw new IllegalArgumentException("Hãy chọn sản phẩm trước khi tìm đơn vị.");if(exact.length()<2)return List.of();
            return Sql.query(c,"SELECT u.id,u.name code,u.name,COALESCE(w.name,'Dùng chung') detail FROM product_units u LEFT JOIN warehouses w ON w.id=u.warehouse_id WHERE u.product_id=? AND (u.name LIKE ? OR w.code LIKE ? OR w.name LIKE ?) AND (? OR u.warehouse_id IS NULL OR EXISTS(SELECT 1 FROM user_warehouses uw WHERE uw.user_id=? AND uw.warehouse_id=u.warehouse_id)) ORDER BY (u.name=?) DESC,(u.name LIKE ?) DESC,u.name,u.id LIMIT 10",product,contains,contains,contains,!a.warehouseScoped(),actor,exact,prefix);});
    }
    public Map<String,Object> find(long actor,long id) {
        return Sql.transaction(source,c->{var access=access(c,actor,"CATALOG_READ");var row=Sql.one(c,"SELECT id,product_id,name,factor,version,is_base,warehouse_id FROM product_units WHERE id=?",id);requireWarehouse(access,row);return row;});
    }
    private static void requireWarehouse(Access access,Map<String,Object> row) {
        if(access.warehouseScoped()&&row.get("warehouse_id")!=null&&!access.managesWarehouse(Sql.id(row.get("warehouse_id"))))throw new SecurityException();
    }
    public long save(long actor,long id,long product,String name,BigDecimal factor,long warehouse,long version) {
        var errors=errors(name,factor,product,version);if(id<0)errors.put("form","Đơn vị không hợp lệ.");
        if(!errors.isEmpty())throw new FieldValidationException(errors);
        String normalizedName=name.trim();BigDecimal normalizedFactor=CatalogValidation.decimal(factor,6,true);
        return Sql.transaction(source,c->{
            var access=access(c,actor,"WAREHOUSE_MANAGE");if(!access.managesWarehouse(warehouse))throw new SecurityException();
            if(Sql.query(c,"SELECT id FROM products WHERE id=? FOR UPDATE",product).isEmpty())throw FieldValidationException.field("form","Sản phẩm không còn tồn tại.");
            Map<String,Object> before=null;
            if(id!=0) {
                var found=Sql.query(c,"SELECT id,product_id,name,factor,version,is_base,warehouse_id FROM product_units WHERE id=? FOR UPDATE",id);
                if(found.isEmpty())throw FieldValidationException.field("form","Đơn vị không còn tồn tại.");
                before=found.get(0);
                if(before.get("warehouse_id")==null)throw FieldValidationException.field("form","Đơn vị cơ sở luôn có hệ số 1 và không thể sửa.");
                if(!access.managesWarehouse(Sql.id(before.get("warehouse_id"))))throw new SecurityException();
                if(Sql.id(before.get("version"))!=version||Sql.id(before.get("product_id"))!=product)throw FieldValidationException.field("form","Đơn vị đã thay đổi. Hãy tải lại.");
            }
            if(!Sql.query(c,"SELECT id FROM product_units WHERE product_id=? AND warehouse_id=? AND name=? AND id<>?",product,warehouse,normalizedName,id).isEmpty())
                throw FieldValidationException.field("name","Đơn vị đã tồn tại trên SKU trong kho này.");
            long saved=id;
            try {
                if(id==0)saved=Sql.insert(c,"INSERT INTO product_units(product_id,name,factor,warehouse_id) VALUES(?,?,?,?)",product,normalizedName,normalizedFactor,warehouse);
                else Sql.update(c,"UPDATE product_units SET name=?,factor=?,warehouse_id=?,version=version+1 WHERE id=?",normalizedName,normalizedFactor,warehouse,id);
            } catch(SQLException failure) {
                if(failure.getErrorCode()==1062&&"23000".equals(failure.getSQLState())&&String.valueOf(failure.getMessage()).contains("uq_product_unit_warehouse"))
                    throw FieldValidationException.field("name","Đơn vị đã tồn tại trên SKU trong kho này.");
                throw failure;
            }
            AuditService.record(c,actor,"UNIT_SAVED","UNIT",saved,before,Sql.one(c,"SELECT id,product_id,name,factor,version,is_base,warehouse_id FROM product_units WHERE id=?",saved));return saved;
        });
    }
    public Conversion convert(long actor,long unit,BigDecimal quantity) {
        return Sql.transaction(source,c->convert(c,access(c,actor,"CATALOG_READ"),unit,quantity));
    }
    public static Conversion convert(Connection c,Access access,long unit,BigDecimal quantity)throws SQLException {
        access.require("CATALOG_READ");BigDecimal normalized=CatalogValidation.decimal(quantity,6,false);
        var row=Sql.one(c,"SELECT id,product_id,name,factor,version,warehouse_id FROM product_units WHERE id=? FOR SHARE",unit);requireWarehouse(access,row);
        BigDecimal factor=(BigDecimal)row.get("factor");Long warehouse=row.get("warehouse_id")==null?null:Sql.id(row.get("warehouse_id"));
        return new Conversion(Sql.id(row.get("product_id")),unit,Sql.text(row.get("name")),normalized,factor,Sql.id(row.get("version")),normalized.multiply(factor),warehouse);
    }
    /** No public catalog permission is granted to a dealer portal by this helper. */
    static Conversion orderConversion(Connection c,long product,long unit,BigDecimal quantity,Long servingWarehouse)throws SQLException {
        BigDecimal normalized=CatalogValidation.decimal(quantity,6,true);
        var row=Sql.one(c,"SELECT id,product_id,name,factor,version,warehouse_id FROM product_units WHERE id=? AND product_id=? FOR SHARE",unit,product);
        Long warehouse=row.get("warehouse_id")==null?null:Sql.id(row.get("warehouse_id"));
        if(warehouse!=null && !warehouse.equals(servingWarehouse))throw new IllegalArgumentException("Đơn vị không thuộc kho phục vụ của đơn.");
        BigDecimal factor=(BigDecimal)row.get("factor");
        return new Conversion(product,unit,Sql.text(row.get("name")),normalized,factor,Sql.id(row.get("version")),normalized.multiply(factor),warehouse);
    }
    /** Called on the same connection as the transaction that records stock. */
    public static long snapshot(Connection c,String reference,Conversion value)throws SQLException {
        return Sql.insert(c,"INSERT INTO conversion_snapshots(reference_id,unit_id,product_id,unit_name,factor,unit_version,quantity,base_quantity,warehouse_id) VALUES(?,?,?,?,?,?,?,?,?)",
                CatalogValidation.text(reference,100,"Tham chiếu"),value.unitId,value.productId,value.unitName,value.factor,value.version,value.quantity,value.baseQuantity,value.warehouseId);
    }
    public void delete(long actor,long id) {delete(actor,id,null);}
    public void delete(long actor,long id,Long product) {
        Sql.transaction(source,c->{
            var access=access(c,actor,"WAREHOUSE_MANAGE");
            var row=Sql.one(c,"SELECT id,product_id,name,factor,version,is_base,warehouse_id FROM product_units WHERE id=? FOR UPDATE",id);
            if(row.get("warehouse_id")==null)throw FieldValidationException.field("form","Không thể xóa đơn vị cơ sở.");
            if(!access.managesWarehouse(Sql.id(row.get("warehouse_id"))))throw new SecurityException();
            if(product!=null&&Sql.id(row.get("product_id"))!=product)throw FieldValidationException.field("form","Đơn vị không thuộc sản phẩm đã chọn.");
            if(!Sql.query(c,"SELECT id FROM order_lines WHERE unit_id=? LIMIT 1",id).isEmpty())throw FieldValidationException.field("form","Đơn vị đã được đơn hàng tham chiếu, không thể xóa.");
            if(!Sql.query(c,"SELECT id FROM conversion_snapshots WHERE unit_id=? LIMIT 1",id).isEmpty())throw FieldValidationException.field("form","Đơn vị đã được tham chiếu, không thể xóa.");
            Sql.update(c,"DELETE FROM product_units WHERE id=?",id);AuditService.record(c,actor,"UNIT_DELETED","UNIT",id,row,null);return null;
        });
    }
}
