package vn.codegym.salesinventory.controller;

import jakarta.servlet.http.*;
import vn.codegym.salesinventory.service.SalesDebtReportService;

/**
 * S7-03, S8-09: Hiển thị công nợ và tuổi nợ theo phạm vi được phép xem.
 */
public final class DebtServlet extends PortalServlet {

    // Nạp dữ liệu và hiển thị trang công nợ đại lý.
    @Override
    protected void get(HttpServletRequest request, HttpServletResponse response) throws Exception {
        var service = new SalesDebtReportService(source());
        Long dealer = value(request, "dealer").isEmpty() ? null : number(request, "dealer");
        if ("export".equals(value(request, "action"))) {
            byte[] file = service.exportDebt(actor(request).id(), dealer, value(request, "q"));
            response.setContentType(
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
            response.setHeader("Content-Disposition", "attachment; filename=cong-no.xlsx");
            response.setHeader("Cache-Control", "no-store");
            response.setContentLength(file.length);
            response.getOutputStream().write(file);
            return;
        }
        request.setAttribute("agingLabels", vn.codegym.salesinventory.service.DebtAging.LABELS);
        request.setAttribute(
                "agingDate",
                java.time.LocalDate.now(vn.codegym.salesinventory.config.VietnamTime.ZONE));
        var report = service.debt(actor(request).id(), dealer, value(request, "q"));
        request.setAttribute("rows", report.rows());
        request.setAttribute("totals", report.totals());
        if (dealer != null) {
            request.setAttribute("dealer", service.dealerDebt(actor(request).id(), dealer));
        }
        view(request, response, "accounting/debt");
    }

    // Tiếp nhận và xử lý thay đổi công nợ đại lý.
    @Override
    protected void post(HttpServletRequest request, HttpServletResponse response) throws Exception {
        response.sendError(405);
    }
}
