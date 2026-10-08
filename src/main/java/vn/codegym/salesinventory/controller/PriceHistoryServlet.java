package vn.codegym.salesinventory.controller;
import jakarta.servlet.http.*;
import java.time.LocalDate;
import vn.codegym.salesinventory.dto.PageRequest;
import vn.codegym.salesinventory.service.*;
import vn.codegym.salesinventory.validation.FieldValidationException;
public final class PriceHistoryServlet extends PortalServlet {
    private PriceHistoryService service(){return new PriceHistoryService(source());}
    private Long id(HttpServletRequest r,String key){return value(r,key).isEmpty()?null:number(r,key);}
    private LocalDate date(HttpServletRequest r,String key){if(value(r,key).isEmpty())return null;try{return LocalDate.parse(value(r,key));}catch(java.time.DateTimeException e){throw FieldValidationException.field(key,"Ngày lọc không hợp lệ.");}}
    protected void get(HttpServletRequest r,HttpServletResponse s)throws Exception{access(r).require("PRICE_READ");r.setAttribute("groups",new PricingService(source()).groups());try{var result=service().search(actor(r).id(),new PriceHistoryService.Filter(value(r,"q"),id(r,"product"),id(r,"group"),date(r,"from"),date(r,"to")),PageRequest.parse(value(r,"page"),value(r,"pageSize")));r.setAttribute("history",result.items());r.setAttribute("pagination",result);}catch(IllegalArgumentException e){s.setStatus(400);r.setAttribute("errors",fieldErrors(e));}view(r,s,"pricing/history");}
}
