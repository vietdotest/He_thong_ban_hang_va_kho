package vn.codegym.salesinventory.controller;
import jakarta.servlet.http.*;
import vn.codegym.salesinventory.dao.Sql;
import vn.codegym.salesinventory.service.AccountStatusService;
public final class AccountStatusServlet extends PortalServlet {
    protected void get(HttpServletRequest r,HttpServletResponse s) throws Exception {
        long id=number(r,"id");Sql.transaction(source(),c -> {r.setAttribute("target",Sql.one(c,"SELECT id,username,full_name,status,lock_reason FROM users WHERE id=?",id));r.setAttribute("warnings",Sql.query(c,"SELECT dealer_reference,reason FROM handover_warnings WHERE user_id=? AND resolved_at IS NULL",id));return null;});view(r,s,"admin/users/status");
    }
    protected void post(HttpServletRequest r,HttpServletResponse s) {
        String action=value(r,"action");if(!action.equals("lock") && !action.equals("unlock"))throw new IllegalArgumentException("Thao tác không hợp lệ.");
        new AccountStatusService(source()).change(actor(r).id(),number(r,"id"),action.equals("lock"),value(r,"reason"));redirect(r,s,"/admin/users/status?id="+number(r,"id"));
    }
}
