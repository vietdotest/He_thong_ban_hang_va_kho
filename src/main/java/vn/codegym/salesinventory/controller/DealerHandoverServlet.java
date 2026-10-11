package vn.codegym.salesinventory.controller;
import jakarta.servlet.http.*;
import vn.codegym.salesinventory.service.DealerHandoverService;
import vn.codegym.salesinventory.dto.PageRequest;
public final class DealerHandoverServlet extends PortalServlet {
    private DealerHandoverService service(){return new DealerHandoverService(source());}
    private void load(HttpServletRequest r){service().options(actor(r).id()).forEach(r::setAttribute);var paging=PageRequest.parse(value(r,"page"),value(r,"pageSize"));if(!value(r,"batch").isEmpty()){r.setAttribute("batch",service().batch(actor(r).id(),value(r,"batch")));var result=service().rows(actor(r).id(),value(r,"batch"),paging);r.setAttribute("rows",result.items());r.setAttribute("pagination",result);}else{var result=service().history(actor(r).id(),paging);r.setAttribute("history",result.items());r.setAttribute("pagination",result);}}
    protected void get(HttpServletRequest r,HttpServletResponse s)throws Exception{load(r);view(r,s,"dealers/handover");}
    protected void post(HttpServletRequest r,HttpServletResponse s)throws Exception{access(r).require("DEALER_HANDOVER");try{String key;if("confirm".equals(value(r,"action"))){key=value(r,"batch");service().confirm(actor(r).id(),key);}else key=service().preview(actor(r).id(),number(r,"fromStaff"),number(r,"toStaff"),value(r,"territory").isEmpty()?null:number(r,"territory"),value(r,"reason"),value(r,"dealerId").isEmpty()?null:number(r,"dealerId"));redirect(r,s,"/dealers/handover?batch="+key);}
        catch(IllegalArgumentException e){s.setStatus(400);r.setAttribute("form",formValues(r,"fromStaff","toStaff","territory","dealerId","reason"));r.setAttribute("error",e.getMessage());load(r);view(r,s,"dealers/handover");}}
}
