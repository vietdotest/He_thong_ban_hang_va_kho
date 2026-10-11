package vn.codegym.salesinventory.controller;
import jakarta.servlet.http.*;
import java.time.*;
import java.sql.Timestamp;
import java.util.*;
import vn.codegym.salesinventory.dao.Sql;
import vn.codegym.salesinventory.dto.*;
import vn.codegym.salesinventory.service.AuditReadService;
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
        try {filter=filter(value(r,"userId"),value(r,"type"),value(r,"from"),value(r,"to"),"1");}
        catch(IllegalArgumentException invalid) {
            s.setStatus(400);r.setAttribute("filterError",invalid.getMessage());r.setAttribute("logs",List.of());r.setAttribute("pageNumber",1);r.setAttribute("pagination",new PageResult<>(List.of(),0,1,20));
            options(r);view(r,s,"admin/audit");return;
        }
        var page=new AuditReadService(source()).search(actor(r).id(),filter.sql(),filter.args().toArray(),value(r,"q"),PageRequest.parse(value(r,"page"),value(r,"pageSize")));
        r.setAttribute("logs",page.items());r.setAttribute("pagination",page);options(r);r.setAttribute("pageNumber",page.page());view(r,s,"admin/audit");
    }
    private void options(HttpServletRequest r) {
        var options=new AuditReadService(source()).options(actor(r).id(),value(r,"userId"));r.setAttribute("users",options.get("users"));r.setAttribute("types",options.get("types"));
    }
}
