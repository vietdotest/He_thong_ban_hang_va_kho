package vn.codegym.salesinventory.controller;

import jakarta.servlet.http.*;
import vn.codegym.salesinventory.service.StockService;

public final class StockServlet extends PortalServlet {
    @Override protected void get(HttpServletRequest r,HttpServletResponse s)throws Exception {
        var service=new StockService(source());Long warehouse=value(r,"warehouse").isEmpty()?null:number(r,"warehouse");r.setAttribute("warehouses",service.warehouses(actor(r).id(),"STOCK_READ"));r.setAttribute("stock",service.stock(actor(r).id(),warehouse,value(r,"q"),value(r,"category").isEmpty()?null:number(r,"category"),value(r,"state")));r.setAttribute("categories",new vn.codegym.salesinventory.service.CategoryService(source()).tree());view(r,s,"inventory/stock");
    }
    @Override protected void post(HttpServletRequest r,HttpServletResponse s)throws Exception{s.sendError(405);}
}
