package vn.codegym.salesinventory.controller;
import jakarta.servlet.http.*;
import vn.codegym.salesinventory.dao.Sql;
import vn.codegym.salesinventory.service.ProfileService;
public final class ProfileServlet extends PortalServlet {
    protected void get(HttpServletRequest r,HttpServletResponse s) throws Exception {
        r.setAttribute("profile",Sql.transaction(source(),c -> Sql.one(c,"SELECT username,email,full_name,phone,avatar_key FROM users WHERE id=?",actor(r).id())));view(r,s,"account/profile");
    }
    protected void post(HttpServletRequest r,HttpServletResponse s) { new ProfileService(source()).update(actor(r).id(),value(r,"fullName"),value(r,"phone"));redirect(r,s,"/account/profile?notice=saved"); }
}
