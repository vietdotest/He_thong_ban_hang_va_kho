package vn.codegym.salesinventory.controller;
import jakarta.servlet.http.*;
import vn.codegym.salesinventory.service.*;
import vn.codegym.salesinventory.dto.PageRequest;
public final class PortalAccountsServlet extends PortalServlet {
    private PortalAccountService service(){return new PortalAccountService(source(),(ActivationService)getServletContext().getAttribute("app.activationService"));}
    private void load(HttpServletRequest r){Long dealer=value(r,"dealer").isEmpty()?null:number(r,"dealer");var p=service().search(actor(r).id(),value(r,"q"),dealer,PageRequest.parse(value(r,"page"),value(r,"pageSize")));r.setAttribute("accounts",p.items());r.setAttribute("pagination",p);if(dealer!=null)r.setAttribute("selectedDealer",new DealerService(source()).find(actor(r).id(),dealer));
        if(!value(r,"userId").isEmpty()){var h=service().linkHistory(actor(r).id(),number(r,"userId"),PageRequest.parse(value(r,"historyPage"),value(r,"historyPageSize")));r.setAttribute("linkHistory",h.items());r.setAttribute("linkHistoryPagination",h);}}
    protected void get(HttpServletRequest r,HttpServletResponse s)throws Exception{load(r);view(r,s,"portal/accounts");}
    protected void post(HttpServletRequest r,HttpServletResponse s)throws Exception{access(r).require("PORTAL_ACCOUNT_MANAGE");try{switch(value(r,"action")){
        case "create"->service().create(actor(r).id(),number(r,"dealer"),value(r,"username"),value(r,"email"),value(r,"fullName"),value(r,"phone"));
        case "resend"->service().resend(actor(r).id(),number(r,"userId"),number(r,"version"));
        case "relink"->service().relink(actor(r).id(),number(r,"userId"),number(r,"version"),number(r,"dealer"),value(r,"reason"));
        case "lock","unlock"->service().status(actor(r).id(),number(r,"userId"),number(r,"version"),"lock".equals(value(r,"action")),value(r,"reason"));
        default->throw new IllegalArgumentException("Thao tác không hợp lệ.");}redirect(r,s,"/dealers/accounts?notice=saved");}
        catch(IllegalArgumentException e){s.setStatus(400);r.setAttribute("form",formValues(r,"dealer","username","email","fullName","phone","userId","version","reason"));r.setAttribute("errors",fieldErrors(e));load(r);view(r,s,"portal/accounts");}}
}
