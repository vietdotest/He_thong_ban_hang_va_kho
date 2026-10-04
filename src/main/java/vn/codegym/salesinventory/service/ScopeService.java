package vn.codegym.salesinventory.service;

import java.sql.SQLException;
import java.util.*;
import javax.sql.DataSource;
import vn.codegym.salesinventory.dao.Sql;
import vn.codegym.salesinventory.validation.FieldValidationException;

public final class ScopeService {
    private final DataSource source;
    public ScopeService(DataSource source) { this.source=source; }
    private static String table(String kind) {
        return switch(kind==null?"":kind) {
            case "warehouse" -> "warehouses";
            case "territory" -> "territories";
            default -> throw FieldValidationException.field("kind","Chọn kho hoặc địa bàn.");
        };
    }
    public List<Map<String,Object>> list(long actor,String kind) {
        new AccessService(source).load(actor).require("USER_MANAGE");
        String table=table(kind),link=kind.equals("warehouse")?"user_warehouses":"user_territories",key=kind.equals("warehouse")?"warehouse_id":"territory_id";
        return Sql.transaction(source,c->Sql.query(c,"SELECT s.id,s.code,s.name,s.address,s.version,(SELECT COUNT(*) FROM "+link+" a WHERE a."+key+"=s.id) assigned_count FROM "+table+" s ORDER BY s.name,s.id"));
    }
    public Map<String,Object> find(long actor,String kind,long id) {
        new AccessService(source).load(actor).require("USER_MANAGE");
        String table=table(kind);
        return Sql.transaction(source,c->Sql.one(c,"SELECT id,code,name,address,version FROM "+table+" WHERE id=?",id));
    }
    public static Map<String,String> errors(String code,String name,String address) {
        var errors=new LinkedHashMap<String,String>();
        if(code==null||!code.trim().matches("[A-Za-z0-9_-]{1,50}"))errors.put("code","Mã gồm 1–50 chữ, số, gạch dưới hoặc gạch ngang.");
        if(name==null||name.isBlank()||name.trim().length()>150)errors.put("name","Nhập tên, tối đa 150 ký tự.");
        if(address==null||address.isBlank()||address.trim().length()>500)errors.put("address","Nhập địa chỉ hoặc khu vực phụ trách, tối đa 500 ký tự.");
        return errors;
    }
    public long save(long actor,String kind,long id,String code,String name,String address,long version) {
        new AccessService(source).load(actor).require("USER_MANAGE");
        String table=table(kind);
        var errors=errors(code,name,address);
        if(id<0||version<0)errors.put("form","Mã bản ghi hoặc phiên bản không hợp lệ.");
        if(!errors.isEmpty())throw new FieldValidationException(errors);
        String normalizedCode=code.trim(),normalizedName=name.trim(),normalizedAddress=address.trim();
        return Sql.transaction(source,c->{
            Map<String,Object> before=id==0?null:Sql.one(c,"SELECT id,code,name,address,version FROM "+table+" WHERE id=? FOR UPDATE",id);
            if(before!=null&&Sql.id(before.get("version"))!=version)throw FieldValidationException.field("form","Kho / địa bàn đã thay đổi. Hãy tải lại trước khi sửa.");
            if(!Sql.query(c,"SELECT id FROM "+table+" WHERE code=? AND id<>?",normalizedCode,id).isEmpty())throw FieldValidationException.field("code","Mã đã tồn tại trong danh mục này.");
            long saved=id;
            try {
                if(id==0)saved=Sql.insert(c,"INSERT INTO "+table+"(code,name,address) VALUES(?,?,?)",normalizedCode,normalizedName,normalizedAddress);
                else Sql.update(c,"UPDATE "+table+" SET code=?,name=?,address=?,version=version+1 WHERE id=?",normalizedCode,normalizedName,normalizedAddress,id);
            } catch(SQLException failure) {
                if(failure.getErrorCode()==1062&&"23000".equals(failure.getSQLState()))throw FieldValidationException.field("code","Mã đã tồn tại trong danh mục này.");
                throw failure;
            }
            AuditService.record(c,actor,id==0?"SCOPE_CREATED":"SCOPE_UPDATED",kind.equals("warehouse")?"WAREHOUSE":"TERRITORY",saved,before,
                Sql.one(c,"SELECT id,code,name,address,version FROM "+table+" WHERE id=?",saved));
            return saved;
        });
    }
}
