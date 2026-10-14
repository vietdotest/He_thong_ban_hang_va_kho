package vn.codegym.salesinventory.controller;

import jakarta.servlet.http.*;
import vn.codegym.salesinventory.service.SalesDebtReportService;

public final class DebtServlet extends PortalServlet {
    @Override protected void get(HttpServletRequest r,HttpServletResponse s)throws Exception {
        var service=new SalesDebtReportService(source());Long dealer=value(r,"dealer").isEmpty()?null:number(r,"dealer");
        var report=service.debt(actor(r).id(),dealer,value(r,"q"));r.setAttribute("rows",report.rows());r.setAttribute("totals",report.totals());if(dealer!=null)r.setAttribute("dealer",service.dealerDebt(actor(r).id(),dealer));view(r,s,"accounting/debt");
    }
    @Override protected void post(HttpServletRequest r,HttpServletResponse s)throws Exception {s.sendError(405);}
}
