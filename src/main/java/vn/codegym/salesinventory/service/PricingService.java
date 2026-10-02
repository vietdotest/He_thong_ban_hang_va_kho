package vn.codegym.salesinventory.service;
import java.math.*;
import java.time.*;
import java.sql.*;
import java.util.*;
import javax.sql.DataSource;
import vn.codegym.salesinventory.dao.Sql;
import vn.codegym.salesinventory.validation.*;
import vn.codegym.salesinventory.security.Access;
public final class PricingService {
    private final DataSource source;
    public PricingService(DataSource source){this.source=source;}
    public record Quote(long itemId,long versionId,long productId,BigDecimal sellingPrice,BigDecimal floorPrice,LocalDate date){
        public boolean requiresApproval(BigDecimal proposed){return CatalogValidation.decimal(proposed,4,false).compareTo(floorPrice)<0;}
    }
    public static Map<String,String> versionErrors(String name,LocalDate from,LocalDate to){
        var errors=new LinkedHashMap<String,String>();
        if(name==null||name.trim().isEmpty()||name.trim().length()>150)errors.put("name","Tên bảng giá phải có từ 1 đến 150 ký tự.");
        if(from==null||from.getYear()<1000||from.getYear()>9999)errors.put("from","Ngày bắt đầu không hợp lệ.");
        if(to==null||to.getYear()<1000||to.getYear()>9999)errors.put("to","Ngày kết thúc không hợp lệ.");
        if(from!=null&&to!=null&&to.isBefore(from))errors.put("to","Ngày kết thúc phải từ ngày bắt đầu trở đi.");
        return errors;
    }
    public static Map<String,String> priceErrors(BigDecimal selling,BigDecimal floor){
        var errors=new LinkedHashMap<String,String>();
        try{CatalogValidation.decimal(selling,4,false);}catch(IllegalArgumentException invalid){errors.put("selling",invalid.getMessage());}
        try{CatalogValidation.decimal(floor,4,false);}catch(IllegalArgumentException invalid){errors.put("floor",invalid.getMessage());}
        if(errors.isEmpty()&&selling.compareTo(floor)<0)errors.put("selling","Giá bán bảng giá phải từ giá sàn trở lên.");
        return errors;
    }
    private static void validVersion(String name,LocalDate from,LocalDate to){var errors=versionErrors(name,from,to);if(!errors.isEmpty())throw new FieldValidationException(errors);}
    public List<Map<String,Object>> groups(){return Sql.transaction(source,c->Sql.query(c,"SELECT id,code,name FROM customer_groups ORDER BY id"));}
    public List<Map<String,Object>> versions(long actor){new AccessService(source).load(actor).require("PRICE_READ");return Sql.transaction(source,c->Sql.query(c,"SELECT v.id,v.group_id,v.name,v.valid_from,v.valid_to,v.parent_id,v.revision,g.name group_name,EXISTS(SELECT 1 FROM price_order_references r WHERE r.version_id=v.id) used FROM price_versions v JOIN customer_groups g ON g.id=v.group_id ORDER BY v.valid_from DESC,v.id DESC"));}
    public List<Map<String,Object>> items(long actor,long version){new AccessService(source).load(actor).require("PRICE_READ");return Sql.transaction(source,c->Sql.query(c,"SELECT i.id,i.product_id,p.sku,p.name,i.selling_price,i.floor_price FROM price_items i JOIN products p ON p.id=i.product_id WHERE version_id=? ORDER BY p.sku",version));}
    private static void lockGroup(Connection c,long group)throws SQLException{if(Sql.query(c,"SELECT id FROM customer_groups WHERE id=? FOR UPDATE",group).isEmpty())throw FieldValidationException.field("group","Nhóm khách không còn tồn tại.");}
    private static void unused(Connection c,long version)throws SQLException{if(!Sql.query(c,"SELECT id FROM price_order_references WHERE version_id=? LIMIT 1 FOR UPDATE",version).isEmpty())throw FieldValidationException.field("form","Phiên bản đã được dùng. Hãy kế thừa thành phiên bản mới.");}
    private static void overlap(Connection c,long group,long product,LocalDate from,LocalDate to,long excluded)throws SQLException{
        if(!Sql.query(c,"SELECT v.id FROM price_versions v JOIN price_items i ON i.version_id=v.id WHERE v.group_id=? AND i.product_id=? AND v.id<>? AND v.valid_from<=? AND v.valid_to>=? LIMIT 1 FOR UPDATE",group,product,excluded,to,from).isEmpty())throw FieldValidationException.field("form","Thời gian giá chồng nhau cho cùng nhóm khách và SKU.");
    }
    public long create(long actor,long group,String name,LocalDate from,LocalDate to,Long parent){
        new AccessService(source).load(actor).require("PRICE_MANAGE");validVersion(name,from,to);
        if(parent!=null&&parent<=0)throw FieldValidationException.field("parent","Phiên bản kế thừa không hợp lệ.");
        return Sql.transaction(source,c->{
            lockGroup(c,group);
            if(parent!=null){
                var base=Sql.one(c,"SELECT group_id FROM price_versions WHERE id=?",parent);
                if(Sql.id(base.get("group_id"))!=group)throw FieldValidationException.field("parent","Phiên bản kế thừa phải cùng nhóm khách.");
                for(var item:Sql.query(c,"SELECT product_id FROM price_items WHERE version_id=? FOR UPDATE",parent))overlap(c,group,Sql.id(item.get("product_id")),from,to,0);
            }
            long id=Sql.insert(c,"INSERT INTO price_versions(group_id,name,valid_from,valid_to,parent_id) VALUES(?,?,?,?,?)",group,name.trim(),from,to,parent);
            if(parent!=null)Sql.update(c,"INSERT INTO price_items(version_id,product_id,selling_price,floor_price) SELECT ?,product_id,selling_price,floor_price FROM price_items WHERE version_id=?",id,parent);
            AuditService.record(c,actor,"PRICE_VERSION_CREATED","PRICE_VERSION",id,null,Sql.one(c,"SELECT id,group_id,name,valid_from,valid_to,parent_id,revision FROM price_versions WHERE id=?",id));return id;
        });
    }
    private static Map<String,Object> lockedVersion(Connection c,long version,Long revision)throws SQLException{
        long group=Sql.id(Sql.one(c,"SELECT group_id FROM price_versions WHERE id=?",version).get("group_id"));lockGroup(c,group);
        var row=Sql.one(c,"SELECT id,name,group_id,valid_from,valid_to,revision FROM price_versions WHERE id=? FOR UPDATE",version);unused(c,version);
        if(revision!=null&&(revision<1||Sql.id(row.get("revision"))!=revision))throw FieldValidationException.field("form","Bảng giá đã thay đổi. Hãy tải lại.");
        return row;
    }
    public void edit(long actor,long id,long revision,String name,LocalDate from,LocalDate to){
        new AccessService(source).load(actor).require("PRICE_MANAGE");validVersion(name,from,to);
        Sql.transaction(source,c->{
            var before=lockedVersion(c,id,revision);long group=Sql.id(before.get("group_id"));
            for(var item:Sql.query(c,"SELECT product_id FROM price_items WHERE version_id=? FOR UPDATE",id))overlap(c,group,Sql.id(item.get("product_id")),from,to,id);
            Sql.update(c,"UPDATE price_versions SET name=?,valid_from=?,valid_to=?,revision=revision+1 WHERE id=?",name.trim(),from,to,id);
            AuditService.record(c,actor,"PRICE_VERSION_UPDATED","PRICE_VERSION",id,before,Sql.one(c,"SELECT id,name,group_id,valid_from,valid_to,revision FROM price_versions WHERE id=?",id));return null;
        });
    }
    public void saveItem(long actor,long version,long product,BigDecimal selling,BigDecimal floor){saveItemChecked(actor,version,product,selling,floor,null);}
    public void saveItem(long actor,long version,long product,BigDecimal selling,BigDecimal floor,long revision){saveItemChecked(actor,version,product,selling,floor,revision);}
    private void saveItemChecked(long actor,long version,long product,BigDecimal selling,BigDecimal floor,Long revision){
        new AccessService(source).load(actor).require("PRICE_MANAGE");var errors=priceErrors(selling,floor);if(!errors.isEmpty())throw new FieldValidationException(errors);
        BigDecimal cleanSelling=CatalogValidation.decimal(selling,4,false),cleanFloor=CatalogValidation.decimal(floor,4,false);
        Sql.transaction(source,c->{
            var v=lockedVersion(c,version,revision);long group=Sql.id(v.get("group_id"));
            if(Sql.query(c,"SELECT id FROM products WHERE id=?",product).isEmpty())throw FieldValidationException.field("product","Sản phẩm không còn tồn tại.");
            overlap(c,group,product,LocalDate.parse(v.get("valid_from").toString()),LocalDate.parse(v.get("valid_to").toString()),version);
            var found=Sql.query(c,"SELECT id,version_id,product_id,selling_price,floor_price FROM price_items WHERE version_id=? AND product_id=? FOR UPDATE",version,product);
            Map<String,Object> before=found.isEmpty()?null:found.get(0);long item;
            if(before==null)item=Sql.insert(c,"INSERT INTO price_items(version_id,product_id,selling_price,floor_price) VALUES(?,?,?,?)",version,product,cleanSelling,cleanFloor);
            else{item=Sql.id(before.get("id"));Sql.update(c,"UPDATE price_items SET selling_price=?,floor_price=? WHERE id=?",cleanSelling,cleanFloor,item);}
            Sql.update(c,"UPDATE price_versions SET revision=revision+1 WHERE id=?",version);
            AuditService.record(c,actor,"PRICE_ITEM_SAVED","PRICE_ITEM",item,before,Sql.one(c,"SELECT id,version_id,product_id,selling_price,floor_price FROM price_items WHERE id=?",item));return null;
        });
    }
    public void deleteItem(long actor,long version,long item){deleteItemChecked(actor,version,item,null);}
    public void deleteItem(long actor,long version,long item,long revision){deleteItemChecked(actor,version,item,revision);}
    private void deleteItemChecked(long actor,long version,long item,Long revision){
        new AccessService(source).load(actor).require("PRICE_MANAGE");
        Sql.transaction(source,c->{
            lockedVersion(c,version,revision);
            var before=Sql.one(c,"SELECT id,version_id,product_id,selling_price,floor_price FROM price_items WHERE id=? AND version_id=?",item,version);
            Sql.update(c,"DELETE FROM price_items WHERE id=?",item);Sql.update(c,"UPDATE price_versions SET revision=revision+1 WHERE id=?",version);
            AuditService.record(c,actor,"PRICE_ITEM_DELETED","PRICE_ITEM",item,before,null);return null;
        });
    }
    public Quote quote(long actor,long group,long product,LocalDate date){var a=new AccessService(source).load(actor);a.require("PRICE_READ");return Sql.transaction(source,c->quote(c,a,group,product,date));}
    /** Đơn hàng gọi quote và snapshot trên cùng connection để khóa phiên bản đã dùng. */
    public static Quote quote(Connection c,Access a,long group,long product,LocalDate date)throws SQLException{
        a.require("PRICE_READ");if(date==null)throw FieldValidationException.field("date","Ngày tra giá không hợp lệ.");
        Sql.one(c,"SELECT id FROM customer_groups WHERE id=? FOR SHARE",group);
        var row=Sql.one(c,"SELECT i.id,i.version_id,i.selling_price,i.floor_price FROM price_items i JOIN price_versions v ON v.id=i.version_id WHERE v.group_id=? AND i.product_id=? AND v.valid_from<=? AND v.valid_to>=?",group,product,date,date);
        return new Quote(Sql.id(row.get("id")),Sql.id(row.get("version_id")),product,(BigDecimal)row.get("selling_price"),(BigDecimal)row.get("floor_price"),date);
    }
    public static long snapshot(Connection c,String orderReference,Quote q)throws SQLException{
        return Sql.insert(c,"INSERT INTO price_order_references(order_reference,price_item_id,version_id,selling_price,floor_price) VALUES(?,?,?,?,?)",CatalogValidation.text(orderReference,100,"Tham chiếu đơn"),q.itemId,q.versionId,q.sellingPrice,q.floorPrice);
    }
}

