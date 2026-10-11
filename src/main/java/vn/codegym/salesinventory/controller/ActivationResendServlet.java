package vn.codegym.salesinventory.controller;
import jakarta.servlet.http.*;
import vn.codegym.salesinventory.service.ActivationService;
public final class ActivationResendServlet extends PortalServlet {
    protected void post(HttpServletRequest r,HttpServletResponse s) {
        ((ActivationService)getServletContext().getAttribute("app.activationService")).resend(actor(r).id(),number(r,"id"));redirect(r,s,"/admin/users");
    }
}
