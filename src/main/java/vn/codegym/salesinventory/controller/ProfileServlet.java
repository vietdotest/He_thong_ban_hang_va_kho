package vn.codegym.salesinventory.controller;
import jakarta.servlet.http.*;
import vn.codegym.salesinventory.dao.Sql;
import vn.codegym.salesinventory.service.ProfileService;
import java.util.Map;
import vn.codegym.salesinventory.validation.ProfileValidationException;
public final class ProfileServlet extends PortalServlet {
    private final ProfileService configuredService;
    public ProfileServlet() { configuredService=null; }
    ProfileServlet(ProfileService service) { configuredService=service; }
    private ProfileService service() { return configuredService==null ? new ProfileService(source()) : configuredService; }
    protected void get(HttpServletRequest r,HttpServletResponse s) throws Exception {
        var profile=service().find(actor(r).id());
        r.setAttribute("profile",profile);
        r.setAttribute("form",Map.of("fullName",Sql.text(profile.get("full_name")),"phone",Sql.text(profile.get("phone"))));
        view(r,s,"account/profile");
    }
    protected void post(HttpServletRequest r,HttpServletResponse s) throws Exception {
        String name=r.getParameter("fullName"),phone=r.getParameter("phone");
        try {
            service().update(actor(r).id(),name,phone);
            redirect(r,s,"/account/profile?notice=saved");
        } catch(ProfileValidationException exception) {
            s.setStatus(400);
            r.setAttribute("profile",service().find(actor(r).id()));
            r.setAttribute("form",Map.of("fullName",name==null ? "" : name,"phone",phone==null ? "" : phone));
            r.setAttribute("errors",exception.errors());
            view(r,s,"account/profile");
        }
    }
}
