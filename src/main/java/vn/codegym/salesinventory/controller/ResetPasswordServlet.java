package vn.codegym.salesinventory.controller;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import vn.codegym.salesinventory.config.ApplicationContextKeys;
import vn.codegym.salesinventory.security.CsrfTokenManager;
import vn.codegym.salesinventory.service.PasswordResetService;
import vn.codegym.salesinventory.validation.PasswordPolicy;

public final class ResetPasswordServlet extends HttpServlet {
    private PasswordResetService passwordResetService;
    private CsrfTokenManager csrfTokenManager;
    private final PasswordPolicy passwordPolicy = new PasswordPolicy();

    @Override
    public void init() throws ServletException {
        Object resetService = getServletContext().getAttribute(ApplicationContextKeys.PASSWORD_RESET_SERVICE);
        Object csrf = getServletContext().getAttribute(ApplicationContextKeys.CSRF_TOKEN_MANAGER);
        if (!(resetService instanceof PasswordResetService configuredReset)
                || !(csrf instanceof CsrfTokenManager configuredCsrf)) {
            throw new ServletException("Password reset components are not initialized");
        }
        passwordResetService = configuredReset;
        csrfTokenManager = configuredCsrf;
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        String token = request.getParameter("token");
        request.setAttribute("token", token);
        request.setAttribute("tokenValid", passwordResetService.isUsable(token));
        request.setAttribute("csrfToken", csrfTokenManager.getOrCreate(request.getSession(true)));
        request.getRequestDispatcher("/WEB-INF/views/auth/reset-password.jsp").forward(request, response);
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        HttpSession session = request.getSession(false);
        String token = request.getParameter("token");
        request.setAttribute("token", token);
        if (!csrfTokenManager.isValid(session, request.getParameter("_csrf"))) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            request.setAttribute("tokenValid", passwordResetService.isUsable(token));
            request.setAttribute("formError", "Phiên biểu mẫu không hợp lệ. Vui lòng tải lại trang.");
            render(request, response);
            return;
        }

        String newPassword = request.getParameter("newPassword");
        String confirmation = request.getParameter("confirmation");
        String passwordError = passwordPolicy.validate(newPassword);
        if (passwordError == null && !newPassword.equals(confirmation)) {
            passwordError = "Mật khẩu xác nhận chưa khớp.";
        }
        if (passwordError != null) {
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            request.setAttribute("tokenValid", passwordResetService.isUsable(token));
            request.setAttribute("passwordError", passwordError);
            render(request, response);
            return;
        }

        try {
            if (!passwordResetService.resetPassword(
                    token, newPassword, RequestMetadata.authenticationContext(request))) {
                response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                request.setAttribute("tokenValid", false);
                render(request, response);
                return;
            }
            session.invalidate();
            RequestMetadata.seeOther(response, request.getContextPath() + "/login?reason=password_reset");
        } catch (RuntimeException exception) {
            getServletContext().log("Password reset could not be completed", exception);
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            request.setAttribute("tokenValid", true);
            request.setAttribute("formError", "Hệ thống chưa thể cập nhật mật khẩu. Vui lòng thử lại.");
            render(request, response);
        }
    }

    private void render(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        request.setAttribute("csrfToken", csrfTokenManager.getOrCreate(request.getSession(true)));
        request.getRequestDispatcher("/WEB-INF/views/auth/reset-password.jsp").forward(request, response);
    }
}
