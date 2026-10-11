package vn.codegym.salesinventory.controller;
import jakarta.servlet.http.*;
import java.util.*;
import vn.codegym.salesinventory.service.ScopeService;
import vn.codegym.salesinventory.dto.PageRequest;
public final class ScopeServlet extends PortalServlet {
    private ScopeService service() { return new ScopeService(source()); }
    protected void get(HttpServletRequest r,HttpServletResponse s) throws Exception {
        for(String kind:List.of("warehouse","territory")){var page=service().search(actor(r).id(),kind,value(r,"q"),PageRequest.parse(value(r,kind+"Page"),value(r,kind+"PageSize")));r.setAttribute(kind+"Pagination",page);r.setAttribute(kind.equals("warehouse")?"warehouses":"territories",page.items());}
        r.setAttribute("scopePaging",paging(r));r.setAttribute("scopeReturn",returnPath(r));
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
        redirect(r,s,returnPath(r)+"&notice=saved");
    }
    private static Map<String,String> paging(HttpServletRequest r){var params=new LinkedHashMap<String,String>();for(String kind:List.of("warehouse","territory")){var page=PageRequest.parse(value(r,kind+"Page"),value(r,kind+"PageSize"));params.put(kind+"Page",Integer.toString(page.page()));params.put(kind+"PageSize",Integer.toString(page.pageSize()));}return params;}
    private static String returnPath(HttpServletRequest r){var params=paging(r);params.put("q",value(r,"q"));return "/admin/scopes?"+params.entrySet().stream().map(p->p.getKey()+"="+java.net.URLEncoder.encode(p.getValue(),java.nio.charset.StandardCharsets.UTF_8)).collect(java.util.stream.Collectors.joining("&"));}
    protected void badRequest(HttpServletRequest r,HttpServletResponse s,String message) throws jakarta.servlet.ServletException,java.io.IOException {
        if(!r.getMethod().equals("POST")){super.badRequest(r,s,message);return;}
        r.setAttribute("form",formValues(r,"kind","id","code","name","address","version"));r.setAttribute("formError",message);s.setStatus(400);
        try { get(r,s); }catch(jakarta.servlet.ServletException|java.io.IOException failure){throw failure;}catch(Exception failure){throw new jakarta.servlet.ServletException(failure);}
    }
}
