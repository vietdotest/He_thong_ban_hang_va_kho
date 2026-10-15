package vn.codegym.salesinventory.controller;

import jakarta.servlet.http.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import vn.codegym.salesinventory.dao.Sql;
import vn.codegym.salesinventory.service.PaymentService;
import vn.codegym.salesinventory.validation.CommandConflictException;

public final class PaymentServlet extends PortalServlet {
    private PaymentService service(){return new PaymentService(source());}
    private void load(HttpServletRequest r,long id,long dealer){
        r.setAttribute("payments",service().payments(actor(r).id()));r.setAttribute("requestKey",UUID.randomUUID().toString());
        if(id>0)r.setAttribute("payment",service().payment(actor(r).id(),id));
        if(access(r).allows("PAYMENT_WRITE")){var debtors=service().debtors(actor(r).id());r.setAttribute("debtors",debtors);for(var customer:debtors)if(Sql.id(customer.get("id"))==dealer)r.setAttribute("selectedDealer",customer);}
        if(r.getAttribute("form")==null)r.setAttribute("form",Map.of("amount","","date",LocalDate.now(vn.codegym.salesinventory.config.VietnamTime.ZONE).toString(),"method","CASH","note",""));
    }
    @Override protected void get(HttpServletRequest r,HttpServletResponse s)throws Exception {load(r,value(r,"id").isEmpty()?0:number(r,"id"),value(r,"dealer").isEmpty()?0:number(r,"dealer"));view(r,s,"accounting/payments");}
    @Override protected void post(HttpServletRequest r,HttpServletResponse s)throws Exception {
        access(r).require("PAYMENT_WRITE");
        if("reverse".equals(value(r,"action"))){long id=number(r,"id");try{service().reverse(actor(r).id(),id,number(r,"version"),value(r,"requestKey"),value(r,"reason"));redirect(r,s,"/accounting/payments?id="+id+"&notice=reversed");}
            catch(IllegalArgumentException e){s.setStatus(e instanceof CommandConflictException?409:400);r.setAttribute("errors",fieldErrors(e));r.setAttribute("reversalReason",value(r,"reason"));load(r,id,0);view(r,s,"accounting/payments");}return;}
        if(!"record".equals(value(r,"action"))){s.sendError(400);return;}long dealer=number(r,"dealer");
        try{if(value(r,"amount").length()>128)throw new IllegalArgumentException("Số tiền không hợp lệ.");var input=new PaymentService.Input(new BigDecimal(value(r,"amount")),LocalDate.parse(value(r,"date")),value(r,"method"),value(r,"note"));long id=service().record(actor(r).id(),dealer,number(r,"version"),value(r,"requestKey"),input);redirect(r,s,"/accounting/payments?id="+id+"&notice=recorded");}
        catch(IllegalArgumentException|java.time.format.DateTimeParseException e){s.setStatus(e instanceof CommandConflictException?409:400);r.setAttribute("errors",e instanceof IllegalArgumentException argument?fieldErrors(argument):Map.of("date","Ngày thu không hợp lệ."));r.setAttribute("form",formValues(r,"amount","date","method","note"));load(r,0,dealer);view(r,s,"accounting/payments");}
    }
}
