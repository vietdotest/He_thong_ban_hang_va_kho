package vn.codegym.salesinventory.controller;

import jakarta.servlet.http.*;
import java.time.LocalDate;
import java.util.Map;
import vn.codegym.salesinventory.service.SalesDebtReportService;

public final class SalesReportServlet extends PortalServlet {
    @Override protected void get(HttpServletRequest r,HttpServletResponse s)throws Exception {
        LocalDate today=LocalDate.now(vn.codegym.salesinventory.config.VietnamTime.ZONE);String from=value(r,"from").isEmpty()?today.withDayOfMonth(1).toString():value(r,"from"),to=value(r,"to").isEmpty()?today.toString():value(r,"to");r.setAttribute("from",from);r.setAttribute("to",to);
        try{Long dealer=value(r,"dealer").isEmpty()?null:number(r,"dealer");var report=new SalesDebtReportService(source()).sales(actor(r).id(),LocalDate.parse(from),LocalDate.parse(to),dealer,value(r,"q"));r.setAttribute("rows",report.rows());r.setAttribute("totals",report.totals());r.setAttribute("documents",report.documents());r.setAttribute("canSeeMargin",report.totals().containsKey("cost"));}
        catch(IllegalArgumentException|java.time.format.DateTimeParseException e){s.setStatus(400);r.setAttribute("errors",Map.of("date","Khoảng ngày hoặc bộ lọc không hợp lệ."));}view(r,s,"reports/sales");
    }
    @Override protected void post(HttpServletRequest r,HttpServletResponse s)throws Exception{s.sendError(405);}
}
