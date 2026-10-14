package vn.codegym.salesinventory.controller;

import jakarta.servlet.http.*;
import java.math.BigDecimal;
import java.util.*;
import vn.codegym.salesinventory.dao.Sql;
import vn.codegym.salesinventory.service.ReturnService;
import vn.codegym.salesinventory.validation.CommandConflictException;

public final class ReturnServlet extends PortalServlet {
    private ReturnService service(){return new ReturnService(source());}
    private static long optional(HttpServletRequest r,String name){return value(r,name).isEmpty()?0:number(r,name);}
    private void load(HttpServletRequest r,long id,long invoice){
        r.setAttribute("returns",service().returns(actor(r).id()));r.setAttribute("requestKey",UUID.randomUUID().toString());
        Map<String,Object> document=id==0?Map.of():service().document(actor(r).id(),id);r.setAttribute("document",document);
        if(access(r).allows("RETURN_WRITE")&&access(r).roles().stream().anyMatch(Set.of("SALES","SALES_MANAGER")::contains))r.setAttribute("invoices",service().invoicesForReturn(actor(r).id()));
        if(Boolean.TRUE.equals(document.get("can_edit")))invoice=Sql.id(document.get("invoice_id"));
        else if(id>0)invoice=0;
        if(invoice>0){var original=service().invoiceForReturn(actor(r).id(),invoice,id);r.setAttribute("sourceInvoice",original);
            if(r.getAttribute("lineValues")==null){var values=new LinkedHashMap<String,Map<String,String>>();@SuppressWarnings("unchecked")var sourceLines=(List<Map<String,Object>>)original.get("lines");for(var line:sourceLines)values.put(line.get("id").toString(),new LinkedHashMap<>(Map.of("sellable","0","damaged","0")));
                if(!document.isEmpty()){@SuppressWarnings("unchecked")var lines=(List<Map<String,Object>>)document.get("lines");for(var line:lines)values.get(line.get("invoice_line_id").toString()).put("SELLABLE".equals(line.get("condition_code"))?"sellable":"damaged",line.get("quantity").toString());}r.setAttribute("lineValues",values);
            }
        }
        if(r.getAttribute("reason")==null)r.setAttribute("reason",Sql.text(document.get("reason")));
    }
    @Override protected void get(HttpServletRequest r,HttpServletResponse s)throws Exception {load(r,optional(r,"id"),optional(r,"invoice"));view(r,s,"accounting/returns");}
    @Override protected void post(HttpServletRequest r,HttpServletResponse s)throws Exception {
        String action=value(r,"action");long id=optional(r,"id"),invoice=optional(r,"invoice");
        try{switch(action){
            case "save"->{access(r).require("RETURN_WRITE");String[] ids=r.getParameterValues("invoiceLine"),good=r.getParameterValues("sellable"),damaged=r.getParameterValues("damaged");if(ids==null||good==null||damaged==null||ids.length!=good.length||ids.length!=damaged.length)throw new IllegalArgumentException("Dòng trả không hợp lệ.");var lines=new ArrayList<ReturnService.Line>();var values=new LinkedHashMap<String,Map<String,String>>();for(int i=0;i<ids.length;i++)values.put(ids[i],Map.of("sellable",good[i],"damaged",damaged[i]));r.setAttribute("lineValues",values);r.setAttribute("reason",value(r,"reason"));
                for(int i=0;i<ids.length;i++){if(good[i].length()>128||damaged[i].length()>128)throw new IllegalArgumentException("Số lượng không hợp lệ.");long line=Long.parseLong(ids[i]);BigDecimal sellable=new BigDecimal(good[i]),broken=new BigDecimal(damaged[i]);if(sellable.signum()!=0)lines.add(new ReturnService.Line(line,sellable,"SELLABLE"));if(broken.signum()!=0)lines.add(new ReturnService.Line(line,broken,"DAMAGED"));}id=service().save(actor(r).id(),id,number(r,"version"),value(r,"requestKey"),new ReturnService.Input(invoice,value(r,"reason"),lines));}
            case "receive"->{access(r).require("STOCK_RETURN_RECEIVE");service().receive(actor(r).id(),id,number(r,"version"),value(r,"requestKey"),"true".equals(value(r,"physicallyConfirmed")));}
            case "cancel"->{access(r).require("RETURN_WRITE");service().cancel(actor(r).id(),id,number(r,"version"),value(r,"requestKey"),value(r,"reason"));}
            default->{s.sendError(400);return;}
        }redirect(r,s,"/accounting/returns?id="+id+"&notice=saved");}
        catch(IllegalArgumentException e){s.setStatus(e instanceof CommandConflictException?409:400);r.setAttribute("errors",fieldErrors(e));load(r,id,invoice);view(r,s,"accounting/returns");}
    }
}
