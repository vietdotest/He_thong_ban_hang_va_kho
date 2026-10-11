package vn.codegym.salesinventory.service;
import java.sql.*;
import java.util.*;
import javax.sql.DataSource;
import vn.codegym.salesinventory.dao.Sql;
import vn.codegym.salesinventory.dto.*;
import vn.codegym.salesinventory.validation.*;
public final class DealerAddressService {
    private final DataSource source;
    public DealerAddressService(DataSource source){this.source=source;}
    public record Input(String address,String recipient,String phone,String directions,boolean defaultPoint,String status,long version,long dealerVersion){}
    public static Map<String,String> errors(Input in){var errors=new LinkedHashMap<String,String>();
        for(var field:List.of(new Object[]{"address",in.address,500,"Địa chỉ"},new Object[]{"recipient",in.recipient,150,"Người nhận"}))try{CatalogValidation.text(clean((String)field[1]),(int)field[2],field[3].toString());}catch(IllegalArgumentException e){errors.put(field[0].toString(),e.getMessage());}
        if(!clean(in.phone).matches("[+0-9() .-]{7,30}"))errors.put("phone","Điện thoại phải có 7–30 ký tự hợp lệ.");
        if(clean(in.directions).length()>1000)errors.put("directions","Ghi chú đường đi tối đa 1.000 ký tự.");
        if(!Set.of("ACTIVE","DISCONTINUED").contains(clean(in.status)))errors.put("status","Trạng thái không hợp lệ.");
        if(in.defaultPoint && "DISCONTINUED".equals(in.status))errors.put("defaultPoint","Điểm mặc định phải đang sử dụng.");
        if(in.version<0 || in.dealerVersion<1)errors.put("form","Phiên bản không hợp lệ.");return errors;
    }
    private static String clean(String v){return v==null?"":v.trim();}
    public static Map<String,Object> belonging(Connection c,long dealer,long id,boolean lock)throws SQLException{
        var rows=Sql.query(c,"SELECT * FROM dealer_addresses WHERE dealer_id=? AND id=?"+(lock?" FOR UPDATE":""),dealer,id);
        if(rows.isEmpty())throw new SecurityException("Điểm giao không thuộc đúng đại lý.");return rows.get(0);
    }
    public PageResult<Map<String,Object>> list(long actor,long dealer,String q,PageRequest request){String search=clean(q);if(search.length()>150)search=search.substring(0,150);String term="%"+search+"%";
        return Sql.transaction(source,c->{var access=DealerService.access(c,actor,"DEALER_READ");DealerService.scoped(c,actor,access,dealer,false);
            String filter=" WHERE dealer_id=? AND (address LIKE ? OR recipient LIKE ? OR phone LIKE ?)";Object[] args={dealer,term,term,term};
            long total=Sql.id(Sql.one(c,"SELECT COUNT(*) total FROM dealer_addresses"+filter,args).get("total"));var p=request.clamp(total);var params=new ArrayList<Object>(Arrays.asList(args));params.add(p.pageSize());params.add(p.offset());
            return new PageResult<>(Sql.query(c,"SELECT * FROM dealer_addresses"+filter+" ORDER BY is_default DESC,id LIMIT ? OFFSET ?",params.toArray()),total,p.page(),p.pageSize());});
    }
    public Map<String,Object> find(long actor,long dealer,long id){return Sql.transaction(source,c->{var access=DealerService.access(c,actor,"DEALER_READ");DealerService.scoped(c,actor,access,dealer,false);return belonging(c,dealer,id,false);});}
    public long save(long actor,long dealer,long id,Input in){var errors=errors(in);if(!errors.isEmpty())throw new FieldValidationException(errors);
        return Sql.transaction(source,c->{var access=DealerService.access(c,actor,"DEALER_ADDRESS_MANAGE");if(!access.roles().stream().anyMatch(Set.of("SALES_MANAGER","SALES")::contains))throw new SecurityException("Không có quyền quản lý điểm giao.");
            var parent=DealerService.scoped(c,actor,access,dealer,true);if(Sql.id(parent.get("version"))!=in.dealerVersion)throw FieldValidationException.field("form","Hồ sơ hoặc điểm mặc định đã thay đổi. Hãy tải lại.");
            var before=id==0?null:belonging(c,dealer,id,true);if(before!=null && Sql.id(before.get("version"))!=in.version)throw FieldValidationException.field("form","Điểm giao đã thay đổi. Hãy tải lại.");
            var defaults=Sql.query(c,"SELECT * FROM dealer_addresses WHERE dealer_id=? AND is_default=1 FOR UPDATE",dealer);
            boolean defaultPoint=in.defaultPoint || (defaults.isEmpty()&&"ACTIVE".equals(in.status));
            if(before!=null && isDefault(before.get("is_default")) && !defaultPoint)throw FieldValidationException.field("defaultPoint","Hãy đặt một điểm khác làm mặc định trước khi ngừng hoặc bỏ mặc định điểm này.");
            if(defaultPoint)for(var old:defaults)if(Sql.id(old.get("id"))!=id){long oldId=Sql.id(old.get("id"));Sql.update(c,"UPDATE dealer_addresses SET is_default=0,version=version+1 WHERE id=?",oldId);AuditService.record(c,actor,"DEALER_ADDRESS_DEFAULT_CHANGED","DEALER_ADDRESS",oldId,old,Sql.one(c,"SELECT * FROM dealer_addresses WHERE id=?",oldId));}
            long saved=id;Object[] args={clean(in.address),clean(in.recipient),clean(in.phone),clean(in.directions),defaultPoint,in.status};
            if(id==0){var params=new ArrayList<Object>();params.add(dealer);params.addAll(Arrays.asList(args));saved=Sql.insert(c,"INSERT INTO dealer_addresses(dealer_id,address,recipient,phone,directions,is_default,status) VALUES(?,?,?,?,?,?,?)",params.toArray());}
            else{var params=new ArrayList<Object>(Arrays.asList(args));params.add(id);Sql.update(c,"UPDATE dealer_addresses SET address=?,recipient=?,phone=?,directions=?,is_default=?,status=?,version=version+1 WHERE id=?",params.toArray());}
            Sql.update(c,"UPDATE dealers SET version=version+1 WHERE id=?",dealer);AuditService.record(c,actor,"DEALER_ADDRESS_SAVED","DEALER_ADDRESS",saved,before,Sql.one(c,"SELECT * FROM dealer_addresses WHERE id=?",saved));return saved;
        });
    }
    public static boolean isDefault(Object value){return Boolean.TRUE.equals(value) || (value instanceof Number n && n.intValue()==1);}
}
