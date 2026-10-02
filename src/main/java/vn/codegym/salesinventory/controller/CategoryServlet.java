package vn.codegym.salesinventory.controller;
import jakarta.servlet.http.*;
import vn.codegym.salesinventory.service.CategoryService;

public final class CategoryServlet extends PortalServlet {
    private final CategoryService configured;
    public CategoryServlet() { configured=null; }
    CategoryServlet(CategoryService configured) { this.configured=configured; }
    private CategoryService service() { return configured==null?new CategoryService(source()):configured; }
    protected void get(HttpServletRequest r,HttpServletResponse s)throws Exception {
        access(r).require("CATALOG_READ");
        r.setAttribute("categories",service().tree());
        if(!value(r,"id").isEmpty())r.setAttribute("edit",service().find(actor(r).id(),number(r,"id")));
        view(r,s,"catalog/categories");
    }
    protected void post(HttpServletRequest r,HttpServletResponse s)throws Exception {
        access(r).require("PRODUCT_MANAGE");
        var form=formValues(r,"id","version","code","name","parent");form.put("parent_id",form.remove("parent"));
        try {
            long id=value(r,"id").isEmpty()?0:number(r,"id");
            if(value(r,"action").equals("delete"))service().delete(actor(r).id(),id);
            else service().save(actor(r).id(),id,value(r,"code"),value(r,"name"),value(r,"parent").isEmpty()?null:number(r,"parent"),value(r,"version").isEmpty()?0:number(r,"version"));
            redirect(r,s,"/catalog/categories?notice=saved");
        } catch(IllegalArgumentException invalid) {
            s.setStatus(400);r.setAttribute("edit",form);r.setAttribute("errors",fieldErrors(invalid));
            r.setAttribute("categories",service().tree());view(r,s,"catalog/categories");
        }
    }
}
