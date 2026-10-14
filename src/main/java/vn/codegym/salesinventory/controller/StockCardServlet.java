package vn.codegym.salesinventory.controller;

import jakarta.servlet.http.*;
import vn.codegym.salesinventory.service.StockService;

public final class StockCardServlet extends PortalServlet {
    @Override protected void get(HttpServletRequest r,HttpServletResponse s)throws Exception {long warehouse=number(r,"warehouse"),product=number(r,"product");var service=new StockService(source());r.setAttribute("cardHeader",service.cardHeader(actor(r).id(),warehouse,product));r.setAttribute("events",service.card(actor(r).id(),warehouse,product));view(r,s,"inventory/card");}
    @Override protected void post(HttpServletRequest r,HttpServletResponse s)throws Exception{s.sendError(405);}
}
