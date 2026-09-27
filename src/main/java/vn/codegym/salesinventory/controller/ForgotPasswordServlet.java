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

public final class ForgotPasswordServlet extends HttpServlet {
    private static final int MAX_IDENTITY_LENGTH = 254;
    private PasswordResetService passwordResetService;
    private CsrfTokenManager csrfTokenManager;

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
        if ("sent".equals(request.getParameter("status"))) {
            request.setAttribute("requestSent", true);
        }
        prepareCsrf(request);
        request.getRequestDispatcher("/WEB-INF/views/auth/forgot-password.jsp").forward(request, response);
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        HttpSession session = request.getSession(false);
        if (!csrfTokenManager.isValid(session, request.getParameter("_csrf"))) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            request.setAttribute("formError", "Phiên biểu mẫu không hợp lệ. Vui lòng tải lại trang.");
            prepareCsrf(request);
            request.getRequestDispatcher("/WEB-INF/views/auth/forgot-password.jsp").forward(request, response);
            return;
        }
        String identity = request.getParameter("identity");
        if (identity == null || identity.isBlank() || identity.length() > MAX_IDENTITY_LENGTH) {
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            request.setAttribute("identity", identity == null ? "" : identity.substring(0, Math.min(identity.length(), MAX_IDENTITY_LENGTH)));
            request.setAttribute("identityError", "Vui lòng nhập tên đăng nhập hoặc email hợp lệ.");
            prepareCsrf(request);
            request.getRequestDispatcher("/WEB-INF/views/auth/forgot-password.jsp").forward(request, response);
            return;
        }
        try {
            passwordResetService.requestReset(identity, RequestMetadata.authenticationContext(request));
            RequestMetadata.seeOther(response, request.getContextPath() + "/forgot-password?status=sent");
        } catch (RuntimeException exception) {
            getServletContext().log("Password reset request could not be processed", exception);
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            request.setAttribute("formError", "Hệ thống chưa thể gửi hướng dẫn. Vui lòng thử lại sau.");
            prepareCsrf(request);
            request.getRequestDispatcher("/WEB-INF/views/auth/forgot-password.jsp").forward(request, response);
        }
    }

    private void prepareCsrf(HttpServletRequest request) {
        request.setAttribute("csrfToken", csrfTokenManager.getOrCreate(request.getSession(true)));
    }
}
