package vn.codegym.salesinventory.controller;
import jakarta.servlet.http.*;
import vn.codegym.salesinventory.service.*;
import vn.codegym.salesinventory.dto.PageRequest;
public final class DealerCreditServlet extends PortalServlet {
    private DealerCreditService service(){return new DealerCreditService(source());}
    private void load(HttpServletRequest r){long dealer=number(r,"dealer");r.setAttribute("dealer",new DealerService(source()).find(actor(r).id(),dealer));var p=service().history(actor(r).id(),dealer,PageRequest.parse(value(r,"page"),value(r,"pageSize")));r.setAttribute("history",p.items());r.setAttribute("pagination",p);}
    protected void get(HttpServletRequest r,HttpServletResponse s)throws Exception{load(r);view(r,s,"dealers/credit");}
    protected void post(HttpServletRequest r,HttpServletResponse s)throws Exception{access(r).require("DEALER_CREDIT_MANAGE");try{long dealer=number(r,"dealer");service().save(actor(r).id(),dealer,number(r,"version"),value(r,"limit"),value(r,"days"),value(r,"reason"));redirect(r,s,"/dealers/credit?dealer="+dealer+"&notice=saved");}
        catch(IllegalArgumentException e){s.setStatus(400);r.setAttribute("form",formValues(r,"limit","days","reason"));r.setAttribute("errors",fieldErrors(e));load(r);view(r,s,"dealers/credit");}}
}
