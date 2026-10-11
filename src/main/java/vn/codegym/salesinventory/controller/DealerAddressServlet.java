package vn.codegym.salesinventory.controller;
import jakarta.servlet.http.*;
import vn.codegym.salesinventory.service.*;
import vn.codegym.salesinventory.dto.PageRequest;
public final class DealerAddressServlet extends PortalServlet {
    private DealerAddressService service(){return new DealerAddressService(source());}
    private void load(HttpServletRequest r){long dealer=number(r,"dealer");r.setAttribute("dealer",new DealerService(source()).find(actor(r).id(),dealer));var p=service().list(actor(r).id(),dealer,value(r,"q"),PageRequest.parse(value(r,"page"),value(r,"pageSize")));r.setAttribute("addresses",p.items());r.setAttribute("pagination",p);}
    protected void get(HttpServletRequest r,HttpServletResponse s)throws Exception{load(r);if(!value(r,"id").isEmpty())r.setAttribute("edit",service().find(actor(r).id(),number(r,"dealer"),number(r,"id")));view(r,s,"dealers/addresses");}
    protected void post(HttpServletRequest r,HttpServletResponse s)throws Exception{
        access(r).require("DEALER_ADDRESS_MANAGE");var form=formValues(r,"id","version","address","recipient","phone","directions","is_default","status");form.put("is_default","1".equals(value(r,"is_default")));
        try{long dealer=number(r,"dealer"),id=value(r,"id").isEmpty()?0:number(r,"id");service().save(actor(r).id(),dealer,id,new DealerAddressService.Input(value(r,"address"),value(r,"recipient"),value(r,"phone"),value(r,"directions"),"1".equals(value(r,"is_default")),value(r,"status"),value(r,"version").isEmpty()?0:number(r,"version"),number(r,"dealerVersion")));redirect(r,s,"/dealers/addresses?dealer="+dealer+"&notice=saved");}
        catch(IllegalArgumentException e){s.setStatus(400);r.setAttribute("edit",form);r.setAttribute("errors",fieldErrors(e));load(r);view(r,s,"dealers/addresses");}
    }
}
