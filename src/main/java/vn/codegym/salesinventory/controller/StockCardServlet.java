package vn.codegym.salesinventory.controller;

import jakarta.servlet.http.*;
import vn.codegym.salesinventory.service.StockService;

public final class StockCardServlet extends PortalServlet {
    @Override protected void get(HttpServletRequest r,HttpServletResponse s)throws Exception {
        long warehouse=number(r,"warehouse"),product=number(r,"product");var service=new StockService(source());
        var from=date(r,"from");var to=date(r,"to");
        r.setAttribute("cardHeader",service.cardHeader(actor(r).id(),warehouse,product));
        r.setAttribute("events",service.card(actor(r).id(),warehouse,product,from,to));view(r,s,"inventory/card");
    }
    private static java.time.LocalDate date(HttpServletRequest r,String name){
        String value=r.getParameter(name);if(value==null||value.isBlank())return null;
        try{return java.time.LocalDate.parse(value);}catch(java.time.format.DateTimeParseException e){throw new IllegalArgumentException("Ngày lọc thẻ kho không hợp lệ.");}
    }
    @Override protected void post(HttpServletRequest r,HttpServletResponse s)throws Exception{s.sendError(405);}
}
