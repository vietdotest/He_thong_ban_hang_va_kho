package vn.codegym.salesinventory.controller;
import jakarta.servlet.http.*;
import vn.codegym.salesinventory.dao.Sql;
import vn.codegym.salesinventory.service.ProfileService;
import vn.codegym.salesinventory.service.ImageStorage;
import vn.codegym.salesinventory.security.CurrentUser;
import vn.codegym.salesinventory.security.SessionKeys;
import jakarta.servlet.ServletException;
import java.io.IOException;
import java.util.Map;
import vn.codegym.salesinventory.validation.ProfileValidationException;
public final class ProfileServlet extends PortalServlet {
    private final ProfileService configuredService;
    public ProfileServlet() { configuredService=null; }
    ProfileServlet(ProfileService service) { configuredService=service; }
    private ProfileService service() { return configuredService==null ? new ProfileService(source()) : configuredService; }
    protected void preparePost(HttpServletRequest r) throws Exception {
        if(!multipart(r)) return;
        try { r.getPart("image"); }
        catch(IllegalStateException tooLarge) { throw new IllegalArgumentException("Ảnh JPG/PNG phải có dung lượng tối đa 2MB.",tooLarge); }
    }
    private boolean multipart(HttpServletRequest r) {
        return r.getContentType()!=null && r.getContentType().toLowerCase(java.util.Locale.ROOT).startsWith("multipart/form-data");
    }
    protected void get(HttpServletRequest r,HttpServletResponse s) throws Exception {
        var profile=service().find(actor(r).id());
        r.setAttribute("profile",profile);
        r.setAttribute("form",Map.of("fullName",Sql.text(profile.get("full_name")),"phone",Sql.text(profile.get("phone"))));
        view(r,s,"account/profile");
    }
    protected void post(HttpServletRequest r,HttpServletResponse s) throws Exception {
        String name=r.getParameter("fullName"),phone=r.getParameter("phone");
        try {
            Part image=multipart(r)?r.getPart("image"):null;
            if(image!=null && image.getSize()>0) {
                if(image.getSize()>ImageStorage.MAX_BYTES) throw new IllegalArgumentException("Ảnh JPG/PNG phải có dung lượng tối đa 2MB.");
                byte[] bytes;
                try(var input=image.getInputStream()) { bytes=input.readNBytes(ImageStorage.MAX_BYTES+1); }
                service().updateWithAvatar(actor(r).id(),name,phone,bytes);
            } else service().update(actor(r).id(),name,phone);
            CurrentUser current=actor(r);
            r.getSession(false).setAttribute(SessionKeys.CURRENT_USER,new CurrentUser(current.id(),current.username(),current.email(),name.trim(),current.roleCodes(),current.mustChangePassword()));
            redirect(r,s,"/account/profile?notice=saved");
        } catch(ProfileValidationException exception) {
            invalid(r,s,exception.errors());
        } catch(IllegalArgumentException exception) {
            invalid(r,s,Map.of("image",exception.getMessage()));
        }
    }
    protected void badRequest(HttpServletRequest r,HttpServletResponse s,String message) throws ServletException,IOException {
        invalid(r,s,Map.of("image",message));
    }
    private void invalid(HttpServletRequest r,HttpServletResponse s,Map<String,String> errors) throws ServletException,IOException {
        s.setStatus(400);
        r.setAttribute("profile",service().find(actor(r).id()));
        r.setAttribute("form",formValues(r,"fullName","phone"));
        r.setAttribute("errors",errors);
        view(r,s,"account/profile");
    }
}
