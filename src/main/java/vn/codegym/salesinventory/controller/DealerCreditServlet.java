package vn.codegym.salesinventory.controller;

import jakarta.servlet.http.*;
import vn.codegym.salesinventory.service.*;
import vn.codegym.salesinventory.dto.PageRequest;

/**
 * S3-05: Hiển thị và cập nhật hạn mức, số ngày nợ của đại lý.
 */
public final class DealerCreditServlet extends PortalServlet {

    private DealerCreditService service() {
        return new DealerCreditService(source());
    }

    // Nạp dữ liệu hạn mức công nợ của đại lý cho màn hình.
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

    // Nạp dữ liệu và hiển thị trang hạn mức công nợ của đại lý.
    protected void get(HttpServletRequest request, HttpServletResponse response) throws Exception {
        load(request);
        view(request, response, "dealers/credit");
    }

    // Tiếp nhận và xử lý thay đổi hạn mức công nợ của đại lý.
    protected void post(HttpServletRequest request, HttpServletResponse response) throws Exception {
        access(request).require("DEALER_CREDIT_MANAGE");
        try {
            long dealer = number(request, "dealer");
            service()
                    .save(
                            actor(request).id(),
                            dealer,
                            number(request, "version"),
                            value(request, "limit"),
                            value(request, "days"),
                            value(request, "reason"));
            redirect(request, response, "/dealers/credit?dealer=" + dealer + "&notice=saved");
        } catch (IllegalArgumentException exception) {
            response.setStatus(400);
            request.setAttribute("form", formValues(request, "limit", "days", "reason"));
            request.setAttribute("errors", fieldErrors(exception));
            load(request);
            view(request, response, "dealers/credit");
        }
    }
}
