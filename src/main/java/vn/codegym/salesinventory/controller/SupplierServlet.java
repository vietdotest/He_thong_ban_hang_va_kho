package vn.codegym.salesinventory.controller;
import jakarta.servlet.http.*;
import vn.codegym.salesinventory.service.SupplierService;
import vn.codegym.salesinventory.dto.PageRequest;
import vn.codegym.salesinventory.validation.FieldValidationException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

public final class SupplierServlet extends PortalServlet {
    private final SupplierService configured;
    public SupplierServlet(){configured=null;}
    SupplierServlet(SupplierService configured){this.configured=configured;}
    private SupplierService service(){return configured==null?new SupplierService(source()):configured;}
    private void options(HttpServletRequest r){
        var page=service().search(actor(r).id(),value(r,"q"),PageRequest.parse(value(r,"page"),value(r,"pageSize")));
        r.setAttribute("suppliers",page.items());r.setAttribute("pagination",page);r.setAttribute("warehouses",access(r).warehouses());
        r.setAttribute("supplierReturn",returnPath(r));
    }
    private static String returnPath(HttpServletRequest r){var page=PageRequest.parse(value(r,"page"),value(r,"pageSize"));return "/catalog/suppliers?q="+URLEncoder.encode(value(r,"q"),StandardCharsets.UTF_8)+"&page="+page.page()+"&pageSize="+page.pageSize();}
    private static long warehouseNumber(HttpServletRequest r){
        try{return number(r,"warehouse");}
        catch(IllegalArgumentException invalid){throw FieldValidationException.field("warehouse","Hãy chọn kho hợp lệ.");}
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
            else service().save(actor(r).id(),id,new SupplierService.Input(value(r,"code"),value(r,"name"),value(r,"taxCode"),value(r,"contact"),value(r,"phone"),value(r,"terms"),warehouseNumber(r),value(r,"status"),value(r,"version").isEmpty()?0:number(r,"version")));
            redirect(r,s,returnPath(r)+"&notice=saved");
        }catch(IllegalArgumentException invalid){
            s.setStatus(400);r.setAttribute("edit",form);r.setAttribute("errors",fieldErrors(invalid));options(r);view(r,s,"catalog/suppliers");
        }
    }
}
