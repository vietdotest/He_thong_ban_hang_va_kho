package vn.codegym.salesinventory.controller;
import jakarta.servlet.http.*;
import vn.codegym.salesinventory.service.*;
import vn.codegym.salesinventory.dto.PageRequest;
public final class DealerStatusServlet extends PortalServlet {
    private DealerStatusService service(){return new DealerStatusService(source());}
    private void load(HttpServletRequest r){long dealer=number(r,"dealer");r.setAttribute("dealer",new DealerService(source()).find(actor(r).id(),dealer));var p=service().history(actor(r).id(),dealer,PageRequest.parse(value(r,"page"),value(r,"pageSize")));r.setAttribute("history",p.items());r.setAttribute("pagination",p);}
    protected void get(HttpServletRequest r,HttpServletResponse s)throws Exception{load(r);view(r,s,"dealers/status");}
    protected void post(HttpServletRequest r,HttpServletResponse s)throws Exception{access(r).require("DEALER_STATUS_MANAGE");try{if(!java.util.Set.of("0","1").contains(value(r,"lock")))throw new IllegalArgumentException("Thao tác khóa/mở không hợp lệ.");long dealer=number(r,"dealer");service().change(actor(r).id(),dealer,number(r,"version"),"1".equals(value(r,"lock")),value(r,"reason"));redirect(r,s,"/dealers/status?dealer="+dealer+"&notice=saved");}
        catch(IllegalArgumentException e){s.setStatus(400);r.setAttribute("form",formValues(r,"reason"));r.setAttribute("errors",fieldErrors(e));load(r);view(r,s,"dealers/status");}}
}
