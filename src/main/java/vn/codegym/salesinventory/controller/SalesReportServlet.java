package vn.codegym.salesinventory.controller;

import jakarta.servlet.http.*;
import java.time.LocalDate;
import java.util.Map;
import vn.codegym.salesinventory.service.SalesDebtReportService;

/**
 * S8-06 (một phần): Hiển thị báo cáo doanh số và lãi gộp theo quyền; chưa đại diện toàn bộ story.
 */
public final class SalesReportServlet extends PortalServlet {

    // Nạp dữ liệu và hiển thị trang báo cáo doanh số.
    @Override
    protected void get(HttpServletRequest request, HttpServletResponse response) throws Exception {
        LocalDate today = LocalDate.now(vn.codegym.salesinventory.config.VietnamTime.ZONE);
        String
                from =
                        value(request, "from").isEmpty()
                                ? today.withDayOfMonth(1).toString()
                                : value(request, "from"),
                to = value(request, "to").isEmpty() ? today.toString() : value(request, "to");
        request.setAttribute("from", from);
        request.setAttribute("to", to);
        try {
            Long dealer = value(request, "dealer").isEmpty() ? null : number(request, "dealer");
            var report =
                    new SalesDebtReportService(source())
                            .sales(
                                    actor(request).id(),
                                    LocalDate.parse(from),
                                    LocalDate.parse(to),
                                    dealer,
                                    value(request, "q"));
            request.setAttribute("rows", report.rows());
            request.setAttribute("totals", report.totals());
            request.setAttribute("documents", report.documents());
            request.setAttribute("canSeeMargin", report.totals().containsKey("cost"));
        } catch (IllegalArgumentException | java.time.format.DateTimeParseException exception) {
            response.setStatus(400);
            request.setAttribute("errors", Map.of("date", "Khoảng ngày hoặc bộ lọc không hợp lệ."));
        }
        view(request, response, "reports/sales");
    }

    // Tiếp nhận và xử lý thay đổi báo cáo doanh số.
    @Override
    protected void post(HttpServletRequest request, HttpServletResponse response) throws Exception {
        response.sendError(405);
    }
}
