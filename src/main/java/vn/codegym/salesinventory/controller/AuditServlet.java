package vn.codegym.salesinventory.controller;
import jakarta.servlet.http.*;
import java.time.*;
import java.sql.Timestamp;
import java.util.*;
import vn.codegym.salesinventory.dao.Sql;
public final class AuditServlet extends PortalServlet {
    protected void get(HttpServletRequest r,HttpServletResponse s) throws Exception {
        StringBuilder filter=new StringBuilder(" WHERE 1=1 ");List<Object> args=new ArrayList<>();
        if(!value(r,"userId").isBlank()) {filter.append(" AND a.actor_user_id=?");args.add(number(r,"userId"));}
        if(!value(r,"type").isBlank()) {filter.append(" AND a.object_type=?");args.add(value(r,"type"));}
        try {
            if(!value(r,"from").isBlank()) {filter.append(" AND a.occurred_at>=?");args.add(Timestamp.from(LocalDate.parse(value(r,"from")).atStartOfDay(ZoneId.of("Asia/Ho_Chi_Minh")).toInstant()));}
            if(!value(r,"to").isBlank()) {filter.append(" AND a.occurred_at<?");args.add(Timestamp.from(LocalDate.parse(value(r,"to")).plusDays(1).atStartOfDay(ZoneId.of("Asia/Ho_Chi_Minh")).toInstant()));}
        } catch(java.time.format.DateTimeParseException e) {throw new IllegalArgumentException("Khoảng ngày không hợp lệ.");}
        int page=value(r,"page").isBlank()?1:Math.toIntExact(number(r,"page"));if(page<1 || page>100000)throw new IllegalArgumentException("Trang không hợp lệ.");
        Sql.transaction(source(),c -> {
            r.setAttribute("users",Sql.query(c,"SELECT id,full_name FROM users ORDER BY full_name"));
            r.setAttribute("types",Sql.query(c,"SELECT DISTINCT object_type FROM audit_logs WHERE object_type IS NOT NULL"));
            r.setAttribute("logs",vn.codegym.salesinventory.service.AuditService.read(c,access(r),filter.toString(),args.toArray(),(page-1)*100));return null;
        });r.setAttribute("pageNumber",page);view(r,s,"admin/audit");
    }
}
