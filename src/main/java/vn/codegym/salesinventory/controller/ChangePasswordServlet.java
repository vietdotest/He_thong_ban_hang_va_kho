package vn.codegym.salesinventory.controller;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import vn.codegym.salesinventory.config.ApplicationContextKeys;
import vn.codegym.salesinventory.security.CsrfTokenManager;
import vn.codegym.salesinventory.security.CurrentUser;
import vn.codegym.salesinventory.security.SessionKeys;
import vn.codegym.salesinventory.service.PasswordChangeService;
import vn.codegym.salesinventory.validation.PasswordPolicy;

public final class ChangePasswordServlet extends HttpServlet {
    private PasswordChangeService passwordChangeService;
    private CsrfTokenManager csrfTokenManager;
    private final PasswordPolicy passwordPolicy = new PasswordPolicy();

    @Override
    public void init() throws ServletException {
        Object changeService = getServletContext().getAttribute(ApplicationContextKeys.PASSWORD_CHANGE_SERVICE);
        Object csrf = getServletContext().getAttribute(ApplicationContextKeys.CSRF_TOKEN_MANAGER);
        if (!(changeService instanceof PasswordChangeService configuredChange)
                || !(csrf instanceof CsrfTokenManager configuredCsrf)) {
            throw new ServletException("Password change components are not initialized");
        }
        passwordChangeService = configuredChange;
        csrfTokenManager = configuredCsrf;
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        prepare(request);
        request.getRequestDispatcher("/WEB-INF/views/account/change-password.jsp").forward(request, response);
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        HttpSession session = request.getSession(false);
        if (!csrfTokenManager.isValid(session, request.getParameter("_csrf"))) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            request.setAttribute("formError", "Phiên biểu mẫu không hợp lệ. Vui lòng tải lại trang.");
            render(request, response);
            return;
        }
        String currentPassword = request.getParameter("currentPassword");
        String newPassword = request.getParameter("newPassword");
        String confirmation = request.getParameter("confirmation");
        if (currentPassword == null || currentPassword.isBlank()) {
            request.setAttribute("currentPasswordError", "Vui lòng nhập mật khẩu hiện tại.");
        }
        String passwordError = passwordPolicy.validate(newPassword);
        if (passwordError == null && !newPassword.equals(confirmation)) {
            passwordError = "Mật khẩu xác nhận chưa khớp.";
        }
        if (request.getAttribute("currentPasswordError") != null || passwordError != null) {
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            request.setAttribute("passwordError", passwordError);
            render(request, response);
            return;
        }

        CurrentUser user = (CurrentUser) session.getAttribute(SessionKeys.CURRENT_USER);
        String serverSessionId = (String) session.getAttribute(SessionKeys.SERVER_SESSION_ID);
        try {
            PasswordChangeService.Result result = passwordChangeService.change(
                    user.id(), serverSessionId, currentPassword, newPassword,
                    RequestMetadata.authenticationContext(request));
            if (result == PasswordChangeService.Result.CURRENT_PASSWORD_INVALID) {
                response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                request.setAttribute("currentPasswordError", "Mật khẩu hiện tại không chính xác.");
            } else if (result == PasswordChangeService.Result.NEW_PASSWORD_UNCHANGED) {
                response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                request.setAttribute("passwordError", "Mật khẩu mới cần khác mật khẩu hiện tại.");
            } else if (result == PasswordChangeService.Result.USER_UNAVAILABLE) {
                session.invalidate();
                RequestMetadata.seeOther(response, request.getContextPath() + "/login?reason=session_expired");
                return;
            } else {
                session.setAttribute(SessionKeys.CURRENT_USER, user.passwordChangeCompleted());
                csrfTokenManager.rotate(session);
                request.setAttribute("successMessage", "Mật khẩu đã được cập nhật. Các phiên đăng nhập khác đã được kết thúc.");
            }
            render(request, response);
        } catch (RuntimeException exception) {
            getServletContext().log("Password change could not be completed", exception);
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            request.setAttribute("formError", "Hệ thống chưa thể cập nhật mật khẩu. Vui lòng thử lại.");
            render(request, response);
        }
    }

    private void render(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        prepare(request);
        request.getRequestDispatcher("/WEB-INF/views/account/change-password.jsp").forward(request, response);
    }

    private void prepare(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        request.setAttribute("currentUser", session.getAttribute(SessionKeys.CURRENT_USER));
        request.setAttribute("csrfToken", csrfTokenManager.getOrCreate(session));
    }
}
