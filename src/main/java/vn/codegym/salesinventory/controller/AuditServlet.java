package vn.codegym.salesinventory.controller;
import jakarta.servlet.http.*;
import java.time.*;
import java.sql.Timestamp;
import java.util.*;
import vn.codegym.salesinventory.dao.Sql;
public final class AuditServlet extends PortalServlet {
    record Filter(String sql,List<Object> args,int page) { }
    static Filter filter(String user,String type,String from,String to,String page) {
        StringBuilder sql=new StringBuilder(" WHERE 1=1 ");List<Object> args=new ArrayList<>();
        if(!user.isBlank()) {
            long id;try {id=Long.parseLong(user);}catch(NumberFormatException invalid){throw new IllegalArgumentException("Người thực hiện không hợp lệ.");}
            if(id<=0)throw new IllegalArgumentException("Người thực hiện không hợp lệ.");sql.append(" AND a.actor_user_id=?");args.add(id);
        }
        if(!type.isBlank()) {sql.append(" AND a.object_type=?");args.add(type);}
        try {
            LocalDate start=from.isBlank()?null:LocalDate.parse(from),end=to.isBlank()?null:LocalDate.parse(to);
            if(start!=null&&end!=null&&end.isBefore(start))throw new IllegalArgumentException("Đến ngày phải từ ngày bắt đầu trở đi.");
            if(start!=null) {sql.append(" AND a.occurred_at>=?");args.add(Timestamp.from(start.atStartOfDay(ZoneId.of("Asia/Ho_Chi_Minh")).toInstant()));}
            if(end!=null) {sql.append(" AND a.occurred_at<?");args.add(Timestamp.from(end.plusDays(1).atStartOfDay(ZoneId.of("Asia/Ho_Chi_Minh")).toInstant()));}
        } catch(java.time.DateTimeException invalid) {throw new IllegalArgumentException("Khoảng ngày không hợp lệ.");}
        long selected;try {selected=page.isBlank()?1:Long.parseLong(page);}catch(NumberFormatException invalid){throw new IllegalArgumentException("Trang không hợp lệ.");}
        if(selected<1||selected>100000)throw new IllegalArgumentException("Trang không hợp lệ.");
        return new Filter(sql.toString(),List.copyOf(args),(int)selected);
    }
    protected void get(HttpServletRequest r,HttpServletResponse s) throws Exception {
        access(r).require("AUDIT_READ");
        Filter filter;
        try {filter=filter(value(r,"userId"),value(r,"type"),value(r,"from"),value(r,"to"),value(r,"page"));}
        catch(IllegalArgumentException invalid) {
            s.setStatus(400);r.setAttribute("filterError",invalid.getMessage());r.setAttribute("logs",List.of());r.setAttribute("pageNumber",1);
            options(r);view(r,s,"admin/audit");return;
        }
        Sql.transaction(source(),c -> {
            r.setAttribute("logs",vn.codegym.salesinventory.service.AuditService.read(c,access(r),filter.sql(),filter.args().toArray(),(filter.page()-1)*100));return null;
        });options(r);r.setAttribute("pageNumber",filter.page());view(r,s,"admin/audit");
    }
    private void options(HttpServletRequest r) {
        Sql.transaction(source(),c -> {
            r.setAttribute("users",Sql.query(c,"SELECT id,full_name FROM users ORDER BY full_name"));
            r.setAttribute("types",Sql.query(c,"SELECT DISTINCT object_type FROM audit_logs WHERE object_type IS NOT NULL"));return null;
        });
    }
}
