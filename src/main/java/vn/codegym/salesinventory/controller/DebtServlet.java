package vn.codegym.salesinventory.controller;

import jakarta.servlet.http.*;
import vn.codegym.salesinventory.service.SalesDebtReportService;

public final class DebtServlet extends PortalServlet {
    @Override protected void get(HttpServletRequest r,HttpServletResponse s)throws Exception {
        var service=new SalesDebtReportService(source());Long dealer=value(r,"dealer").isEmpty()?null:number(r,"dealer");
        if("export".equals(value(r,"action"))){byte[] file=service.exportDebt(actor(r).id(),dealer,value(r,"q"));s.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");s.setHeader("Content-Disposition","attachment; filename=cong-no.xlsx");s.setHeader("Cache-Control","no-store");s.setContentLength(file.length);s.getOutputStream().write(file);return;}
        r.setAttribute("agingLabels",vn.codegym.salesinventory.service.DebtAging.LABELS);r.setAttribute("agingDate",java.time.LocalDate.now(vn.codegym.salesinventory.config.VietnamTime.ZONE));
        var report=service.debt(actor(r).id(),dealer,value(r,"q"));r.setAttribute("rows",report.rows());r.setAttribute("totals",report.totals());if(dealer!=null)r.setAttribute("dealer",service.dealerDebt(actor(r).id(),dealer));view(r,s,"accounting/debt");
    }
    @Override protected void post(HttpServletRequest r,HttpServletResponse s)throws Exception {s.sendError(405);}
}
