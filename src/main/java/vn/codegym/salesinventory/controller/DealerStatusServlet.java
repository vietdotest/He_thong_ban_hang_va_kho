package vn.codegym.salesinventory.controller;

import jakarta.servlet.http.*;
import vn.codegym.salesinventory.service.*;
import vn.codegym.salesinventory.dto.PageRequest;

/**
 * S3-07: Nhận yêu cầu khóa, mở giao dịch và hiển thị lịch sử của đại lý.
 */
public final class DealerStatusServlet extends PortalServlet {

    private DealerStatusService service() {
        return new DealerStatusService(source());
    }

    // Nạp dữ liệu trạng thái giao dịch của đại lý cho màn hình.
    private void load(HttpServletRequest request) {
        long dealer = number(request, "dealer");
        request.setAttribute(
                "dealer", new DealerService(source()).find(actor(request).id(), dealer));
        var p =
                service()
                        .history(
                                actor(request).id(),
                                dealer,
                                PageRequest.parse(
                                        value(request, "page"), value(request, "pageSize")));
        request.setAttribute("history", p.items());
        request.setAttribute("pagination", p);
    }

    // Nạp dữ liệu và hiển thị trang trạng thái giao dịch của đại lý.
    protected void get(HttpServletRequest request, HttpServletResponse response) throws Exception {
        load(request);
        view(request, response, "dealers/status");
    }

    // Tiếp nhận và xử lý thay đổi trạng thái giao dịch của đại lý.
    protected void post(HttpServletRequest request, HttpServletResponse response) throws Exception {
        access(request).require("DEALER_STATUS_MANAGE");
        try {
            if (!java.util.Set.of("0", "1").contains(value(request, "lock"))) {
                throw new IllegalArgumentException("Thao tác khóa/mở không hợp lệ.");
            }
            long dealer = number(request, "dealer");
            service()
                    .change(
                            actor(request).id(),
                            dealer,
                            number(request, "version"),
                            "1".equals(value(request, "lock")),
                            value(request, "reason"));
            redirect(request, response, "/dealers/status?dealer=" + dealer + "&notice=saved");
        } catch (IllegalArgumentException exception) {
            response.setStatus(400);
            request.setAttribute("form", formValues(request, "reason"));
            request.setAttribute("errors", fieldErrors(exception));
            load(request);
            view(request, response, "dealers/status");
        }
    }
}
