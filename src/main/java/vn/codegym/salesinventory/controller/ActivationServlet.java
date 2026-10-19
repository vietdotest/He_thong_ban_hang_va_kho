package vn.codegym.salesinventory.controller;

import jakarta.servlet.http.*;
import vn.codegym.salesinventory.security.CsrfTokenManager;
import vn.codegym.salesinventory.config.ApplicationContextKeys;
import vn.codegym.salesinventory.service.ActivationService;

/**
 * S1-08: Hiển thị trang và tiếp nhận xác nhận kích hoạt tài khoản.
 */
public final class ActivationServlet extends PortalServlet {

    // Nạp dữ liệu và hiển thị trang kích hoạt tài khoản.
    protected void get(HttpServletRequest request, HttpServletResponse response) throws Exception {
        request.setAttribute("token", value(request, "token"));
        request.setAttribute(
                "csrfToken",
                ((CsrfTokenManager)
                                getServletContext()
                                        .getAttribute(ApplicationContextKeys.CSRF_TOKEN_MANAGER))
                        .getOrCreate(request.getSession(true)));
        view(request, response, "auth/activate");
    }

    // Tiếp nhận và xử lý thay đổi kích hoạt tài khoản.
    protected void post(HttpServletRequest request, HttpServletResponse response) throws Exception {
        if (!((ActivationService) getServletContext().getAttribute("app.activationService"))
                .activate(value(request, "token"))) {
            throw new IllegalArgumentException(
                    "Liên kết kích hoạt đã hết hạn, đã được dùng hoặc không hợp lệ. Vui lòng liên"
                            + " hệ quản trị để gửi lại.");
        }
        redirect(request, response, "/login?reason=activated");
    }
}
