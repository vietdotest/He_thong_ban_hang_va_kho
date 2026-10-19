package vn.codegym.salesinventory.controller;

import jakarta.servlet.http.*;
import vn.codegym.salesinventory.service.ActivationService;

/**
 * S1-08: Nhận yêu cầu gửi lại email kích hoạt tài khoản.
 */
public final class ActivationResendServlet extends PortalServlet {

    // Tiếp nhận và xử lý thay đổi gửi lại email kích hoạt.
    protected void post(HttpServletRequest request, HttpServletResponse response) {
        ((ActivationService) getServletContext().getAttribute("app.activationService"))
                .resend(actor(request).id(), number(request, "id"));
        redirect(request, response, "/admin/users");
    }
}
