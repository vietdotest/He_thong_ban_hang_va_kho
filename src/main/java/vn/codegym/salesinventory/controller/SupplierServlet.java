package vn.codegym.salesinventory.controller;
import jakarta.servlet.http.*;
import vn.codegym.salesinventory.service.SupplierService;

public final class SupplierServlet extends PortalServlet {
    private final SupplierService configured;
    public SupplierServlet(){configured=null;}
    SupplierServlet(SupplierService configured){this.configured=configured;}
    private SupplierService service(){return configured==null?new SupplierService(source()):configured;}
    private void options(HttpServletRequest r){
        r.setAttribute("suppliers",service().list(actor(r).id(),value(r,"q")));r.setAttribute("warehouses",access(r).warehouses());
    }
    protected void get(HttpServletRequest r,HttpServletResponse s)throws Exception{
        access(r).require("CATALOG_READ");options(r);
        if(!value(r,"id").isEmpty())r.setAttribute("edit",service().find(actor(r).id(),number(r,"id")));view(r,s,"catalog/suppliers");
    }
    protected void post(HttpServletRequest r,HttpServletResponse s)throws Exception{
        access(r).require("WAREHOUSE_MANAGE");
        var form=formValues(r,"id","version","code","name","taxCode","contact","phone","terms","warehouse","status");
        for(var pair:new String[][]{{"taxCode","tax_code"},{"contact","contact_name"},{"phone","contact_phone"},{"terms","payment_terms"},{"warehouse","warehouse_id"}})form.put(pair[1],form.remove(pair[0]));
        try{
            long id=value(r,"id").isEmpty()?0:number(r,"id");
            if(value(r,"action").equals("delete"))service().delete(actor(r).id(),id);
            else service().save(actor(r).id(),id,new SupplierService.Input(value(r,"code"),value(r,"name"),value(r,"taxCode"),value(r,"contact"),value(r,"phone"),value(r,"terms"),number(r,"warehouse"),value(r,"status"),value(r,"version").isEmpty()?0:number(r,"version")));
            redirect(r,s,"/catalog/suppliers?notice=saved");
        }catch(IllegalArgumentException invalid){
            s.setStatus(400);r.setAttribute("edit",form);r.setAttribute("errors",fieldErrors(invalid));options(r);view(r,s,"catalog/suppliers");
        }
    }
}
