package vn.codegym.salesinventory.service;
import java.sql.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import javax.sql.DataSource;
import vn.codegym.salesinventory.config.VietnamTime;
import vn.codegym.salesinventory.dao.Sql;
import vn.codegym.salesinventory.dto.*;
public final class PriceHistoryService {
    private final DataSource source;
    public PriceHistoryService(DataSource source){this.source=source;}
    public record Filter(String query,Long product,Long group,LocalDate from,LocalDate to){}
    /** Only called by pricing writes on THEIR connection; history has no public update/delete API. */
    static void record(Connection c,long actor,String event,long product,long version,Long item,Map<String,Object> before,Map<String,Object> after,Map<String,Object> oldVersion,Map<String,Object> newVersion)throws SQLException{
        var p=Sql.one(c,"SELECT sku,name FROM products WHERE id=?",product);long group=Sql.id(newVersion.get("group_id"));var g=Sql.one(c,"SELECT name FROM customer_groups WHERE id=?",group);var a=Sql.one(c,"SELECT full_name FROM users WHERE id=?",actor);
        Sql.insert(c,"INSERT INTO price_history(product_id,sku_snapshot,product_name_snapshot,version_id,price_item_id,group_id,group_name_snapshot,actor_id,actor_name_snapshot,event_type,old_selling,new_selling,old_floor,new_floor,old_from,old_to,new_from,new_to) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",product,p.get("sku"),p.get("name"),version,item,group,g.get("name"),actor,a.get("full_name"),event,before==null?null:before.get("selling_price"),after==null?null:after.get("selling_price"),before==null?null:before.get("floor_price"),after==null?null:after.get("floor_price"),oldVersion==null?null:oldVersion.get("valid_from"),oldVersion==null?null:oldVersion.get("valid_to"),newVersion.get("valid_from"),newVersion.get("valid_to"));
    }
    public PageResult<Map<String,Object>> search(long actor,Filter filter,PageRequest request){String q=filter.query==null?"":filter.query.trim();if(q.length()>150)q=q.substring(0,150);final String term="%"+q+"%";
        if(filter.from!=null&&filter.to!=null&&filter.to.isBefore(filter.from))throw new IllegalArgumentException("Ngày kết thúc phải từ ngày bắt đầu trở đi.");
        for(var day:new LocalDate[]{filter.from,filter.to})if(day!=null&&(day.getYear()<1000||day.getYear()>9999))throw new IllegalArgumentException("Ngày lọc vượt giới hạn dữ liệu.");
        Timestamp from=filter.from==null?null:Timestamp.from(filter.from.atStartOfDay(VietnamTime.ZONE).toInstant()),to=filter.to==null||filter.to.equals(LocalDate.of(9999,12,31))?null:Timestamp.from(filter.to.plusDays(1).atStartOfDay(VietnamTime.ZONE).toInstant());
        return Sql.snapshot(source,c->{DealerService.access(c,actor,"PRICE_READ");String where=" WHERE (h.sku_snapshot LIKE ? OR h.product_name_snapshot LIKE ?) AND (? IS NULL OR h.product_id=?) AND (? IS NULL OR h.group_id=?) AND (? IS NULL OR h.changed_at>=?) AND (? IS NULL OR h.changed_at<?)";Object[] args={term,term,filter.product,filter.product,filter.group,filter.group,from,from,to,to};long total=Sql.id(Sql.one(c,"SELECT COUNT(*) total FROM price_history h"+where,args).get("total"));var p=request.clamp(total);var params=new ArrayList<Object>(Arrays.asList(args));params.add(p.pageSize());params.add(p.offset());var rows=Sql.query(c,"SELECT h.* FROM price_history h"+where+" ORDER BY h.changed_at DESC,h.id DESC LIMIT ? OFFSET ?",params.toArray());for(var row:rows){row.put("display_time",Sql.instant(row.get("changed_at")).atZone(VietnamTime.ZONE).format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss")));row.put("event_label",switch(Sql.text(row.get("event_type"))){case "ITEM_CREATED"->"Thêm giá";case "ITEM_UPDATED"->"Sửa giá";case "ITEM_DELETED"->"Xóa giá";case "ITEM_INHERITED"->"Kế thừa giá";default->"Đổi hiệu lực / tên bảng giá";});}return new PageResult<>(rows,total,p.page(),p.pageSize());});
    }
}
