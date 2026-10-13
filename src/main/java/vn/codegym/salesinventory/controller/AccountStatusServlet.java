package vn.codegym.salesinventory.controller;
import jakarta.servlet.http.*;
import vn.codegym.salesinventory.dao.Sql;
import vn.codegym.salesinventory.service.AccountStatusService;
public final class AccountStatusServlet extends PortalServlet {
    protected void get(HttpServletRequest r,HttpServletResponse s) throws Exception {
        long id=number(r,"id");Sql.transaction(source(),c -> {r.setAttribute("target",Sql.one(c,"SELECT id,username,full_name,status,lock_reason FROM users WHERE id=?",id));r.setAttribute("assignmentCount",Sql.id(Sql.one(c,"SELECT COUNT(*) total FROM dealers WHERE primary_staff_id=?",id).get("total")));r.setAttribute("warnings",Sql.query(c,"SELECT d.id,d.code,d.name,COALESCE(MAX(h.reason),'Nhân viên vẫn phụ trách đại lý này.') reason FROM dealers d LEFT JOIN handover_warnings h ON h.user_id=? AND h.dealer_reference=CONCAT('DEALER:',d.id) AND h.resolved_at IS NULL WHERE d.primary_staff_id=? GROUP BY d.id,d.code,d.name ORDER BY d.code,d.id LIMIT 20",id,id));return null;});r.setAttribute("canHandover",access(r).allows("DEALER_HANDOVER")&&access(r).roles().contains("SALES_MANAGER")&&vn.codegym.salesinventory.service.DealerService.all(access(r)));view(r,s,"admin/users/status");
    }
    protected void post(HttpServletRequest r,HttpServletResponse s) {
        String action=value(r,"action");if(!action.equals("lock") && !action.equals("unlock"))throw new IllegalArgumentException("Thao tác không hợp lệ.");
        new AccountStatusService(source()).change(actor(r).id(),number(r,"id"),action.equals("lock"),value(r,"reason"));redirect(r,s,"/admin/users/status?id="+number(r,"id")+"&notice=saved");
    }
    protected void badRequest(HttpServletRequest r,HttpServletResponse s,String message) throws jakarta.servlet.ServletException,java.io.IOException {
        r.setAttribute("formError",message);r.setAttribute("reason",value(r,"reason"));s.setStatus(400);
        try {get(r,s);}catch(IllegalArgumentException invalid){super.badRequest(r,s,message);}catch(jakarta.servlet.ServletException|java.io.IOException failure){throw failure;}catch(Exception failure){throw new jakarta.servlet.ServletException(failure);}
    }
}
