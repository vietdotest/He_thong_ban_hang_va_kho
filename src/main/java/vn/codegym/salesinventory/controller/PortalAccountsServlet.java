package vn.codegym.salesinventory.controller;

import jakarta.servlet.http.*;
import vn.codegym.salesinventory.service.*;
import vn.codegym.salesinventory.dto.PageRequest;

/**
 * S4-10: Quản lý tài khoản sử dụng cổng đặt hàng của đại lý.
 */
public final class PortalAccountsServlet extends PortalServlet {

    private PortalAccountService service() {
        return new PortalAccountService(
                source(),
                (ActivationService) getServletContext().getAttribute("app.activationService"));
    }

    // Nạp dữ liệu tài khoản cổng đại lý cho màn hình.
    private void load(HttpServletRequest request) {
        Long dealer = value(request, "dealer").isEmpty() ? null : number(request, "dealer");
        var p =
                service()
                        .search(
                                actor(request).id(),
                                value(request, "q"),
                                dealer,
                                PageRequest.parse(
                                        value(request, "page"), value(request, "pageSize")));
        request.setAttribute("accounts", p.items());
        request.setAttribute("pagination", p);
        if (dealer != null) {
            request.setAttribute(
                    "selectedDealer",
                    new DealerService(source()).find(actor(request).id(), dealer));
        }
        if (!value(request, "userId").isEmpty()) {
            var h =
                    service()
                            .linkHistory(
                                    actor(request).id(),
                                    number(request, "userId"),
                                    PageRequest.parse(
                                            value(request, "historyPage"),
                                            value(request, "historyPageSize")));
            request.setAttribute("linkHistory", h.items());
            request.setAttribute("linkHistoryPagination", h);
        }
    }

    // Nạp dữ liệu và hiển thị trang tài khoản cổng đại lý.
    protected void get(HttpServletRequest request, HttpServletResponse response) throws Exception {
        load(request);
        view(request, response, "portal/accounts");
    }

    // Tiếp nhận và xử lý thay đổi tài khoản cổng đại lý.
    protected void post(HttpServletRequest request, HttpServletResponse response) throws Exception {
        access(request).require("PORTAL_ACCOUNT_MANAGE");
        try {
            switch (value(request, "action")) {
                case "create" ->
                        service()
                                .create(
                                        actor(request).id(),
                                        number(request, "dealer"),
                                        value(request, "username"),
                                        value(request, "email"),
                                        value(request, "fullName"),
                                        value(request, "phone"));
                case "resend" ->
                        service()
                                .resend(
                                        actor(request).id(),
                                        number(request, "userId"),
                                        number(request, "version"));
                case "relink" ->
                        service()
                                .relink(
                                        actor(request).id(),
                                        number(request, "userId"),
                                        number(request, "version"),
                                        number(request, "dealer"),
                                        value(request, "reason"));
                case "lock", "unlock" ->
                        service()
                                .status(
                                        actor(request).id(),
                                        number(request, "userId"),
                                        number(request, "version"),
                                        "lock".equals(value(request, "action")),
                                        value(request, "reason"));
                default -> throw new IllegalArgumentException("Thao tác không hợp lệ.");
            }
            redirect(request, response, "/dealers/accounts?notice=saved");
        } catch (IllegalArgumentException exception) {
            response.setStatus(400);
            request.setAttribute(
                    "form",
                    formValues(
                            request,
                            "dealer",
                            "username",
                            "email",
                            "fullName",
                            "phone",
                            "userId",
                            "version",
                            "reason"));
            request.setAttribute("errors", fieldErrors(exception));
            load(request);
            view(request, response, "portal/accounts");
        }
    }
}
