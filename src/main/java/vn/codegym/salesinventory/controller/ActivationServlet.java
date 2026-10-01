package vn.codegym.salesinventory.controller;
import jakarta.servlet.http.*;
import vn.codegym.salesinventory.security.CsrfTokenManager;
import vn.codegym.salesinventory.config.ApplicationContextKeys;
import vn.codegym.salesinventory.service.ActivationService;
public final class ActivationServlet extends PortalServlet {
    protected void get(HttpServletRequest r,HttpServletResponse s) throws Exception {
        r.setAttribute("token",value(r,"token"));
        r.setAttribute("csrfToken",((CsrfTokenManager)getServletContext().getAttribute(ApplicationContextKeys.CSRF_TOKEN_MANAGER)).getOrCreate(r.getSession(true)));
        view(r,s,"auth/activate");
    }
    protected void post(HttpServletRequest r,HttpServletResponse s) throws Exception {
        if(!((ActivationService)getServletContext().getAttribute("app.activationService")).activate(value(r,"token")))throw new IllegalArgumentException("Liên kết kích hoạt đã hết hạn, đã được dùng hoặc không hợp lệ. Vui lòng liên hệ quản trị để gửi lại.");
        redirect(r,s,"/login?reason=activated");
    }
}
