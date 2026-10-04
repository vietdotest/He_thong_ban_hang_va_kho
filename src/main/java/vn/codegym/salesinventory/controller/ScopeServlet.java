package vn.codegym.salesinventory.controller;
import jakarta.servlet.http.*;
import java.util.*;
import vn.codegym.salesinventory.service.ScopeService;
public final class ScopeServlet extends PortalServlet {
    private ScopeService service() { return new ScopeService(source()); }
    protected void get(HttpServletRequest r,HttpServletResponse s) throws Exception {
        r.setAttribute("warehouses",service().list(actor(r).id(),"warehouse"));
        r.setAttribute("territories",service().list(actor(r).id(),"territory"));
        if(r.getAttribute("form")==null) {
            String kind=value(r,"kind").equals("territory")?"territory":"warehouse";
            if(!value(r,"edit").isEmpty()) {
                var form=new LinkedHashMap<>(service().find(actor(r).id(),kind,number(r,"edit")));form.put("kind",kind);r.setAttribute("form",form);
            } else r.setAttribute("form",Map.of("kind",kind));
        }
        view(r,s,"admin/scopes");
    }
    protected void post(HttpServletRequest r,HttpServletResponse s) throws Exception {
        try {
            service().save(actor(r).id(),value(r,"kind"),value(r,"id").isEmpty()?0:number(r,"id"),value(r,"code"),value(r,"name"),value(r,"address"),value(r,"version").isEmpty()?0:number(r,"version"));
        } catch(vn.codegym.salesinventory.validation.FieldValidationException invalid) { r.setAttribute("errors",invalid.errors());throw invalid; }
        redirect(r,s,"/admin/scopes?notice=saved");
    }
    protected void badRequest(HttpServletRequest r,HttpServletResponse s,String message) throws jakarta.servlet.ServletException,java.io.IOException {
        if(!r.getMethod().equals("POST")){super.badRequest(r,s,message);return;}
        r.setAttribute("form",formValues(r,"kind","id","code","name","address","version"));r.setAttribute("formError",message);s.setStatus(400);
        try { get(r,s); }catch(jakarta.servlet.ServletException|java.io.IOException failure){throw failure;}catch(Exception failure){throw new jakarta.servlet.ServletException(failure);}
    }
}
