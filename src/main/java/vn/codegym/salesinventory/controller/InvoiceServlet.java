package vn.codegym.salesinventory.controller;

import jakarta.servlet.http.*;
import java.util.UUID;
import vn.codegym.salesinventory.service.InvoiceService;
import vn.codegym.salesinventory.validation.CommandConflictException;

public final class InvoiceServlet extends PortalServlet {
    private InvoiceService service(){return new InvoiceService(source());}
    private void load(HttpServletRequest r,long id){
        r.setAttribute("invoices",service().invoices(actor(r).id()));r.setAttribute("requestKey",UUID.randomUUID().toString());
        if(id>0)r.setAttribute("invoice",service().invoice(actor(r).id(),id));
        if(access(r).allows("INVOICE_ISSUE")){var deliveries=service().availableDeliveries(actor(r).id());for(var delivery:deliveries)delivery.put("request_key",UUID.randomUUID().toString());r.setAttribute("deliveries",deliveries);}
    }
    @Override protected void get(HttpServletRequest r,HttpServletResponse s)throws Exception {load(r,value(r,"id").isEmpty()?0:number(r,"id"));view(r,s,"accounting/invoices");}
    @Override protected void post(HttpServletRequest r,HttpServletResponse s)throws Exception {
        access(r).require("INVOICE_ISSUE");if(!"issue".equals(value(r,"action"))){s.sendError(400);return;}
        try{long id=service().issue(actor(r).id(),number(r,"shipment"),number(r,"version"),value(r,"requestKey"));redirect(r,s,"/accounting/invoices?id="+id+"&notice=issued");}
        catch(IllegalArgumentException e){s.setStatus(e instanceof CommandConflictException?409:400);r.setAttribute("errors",fieldErrors(e));load(r,0);view(r,s,"accounting/invoices");}
    }
}
