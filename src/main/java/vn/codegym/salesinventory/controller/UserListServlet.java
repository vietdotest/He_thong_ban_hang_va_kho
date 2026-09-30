package vn.codegym.salesinventory.controller;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import vn.codegym.salesinventory.config.ApplicationContextKeys;
import vn.codegym.salesinventory.dto.UserSearchCriteria;
import vn.codegym.salesinventory.model.UserStatus;
import vn.codegym.salesinventory.security.CsrfTokenManager;
import vn.codegym.salesinventory.security.SessionKeys;
import vn.codegym.salesinventory.service.UserManagementService;

public final class UserListServlet extends HttpServlet {
    private UserManagementService userManagementService;
    private CsrfTokenManager csrfTokenManager;

    @Override
    public void init() throws ServletException {
        Object service = getServletContext().getAttribute(ApplicationContextKeys.USER_MANAGEMENT_SERVICE);
        Object csrf = getServletContext().getAttribute(ApplicationContextKeys.CSRF_TOKEN_MANAGER);
        if (!(service instanceof UserManagementService configuredService)
                || !(csrf instanceof CsrfTokenManager configuredCsrf)) {
            throw new ServletException("User-management components are not initialized");
        }
        userManagementService = configuredService;
        csrfTokenManager = configuredCsrf;
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        UserSearchCriteria criteria = new UserSearchCriteria(
                limited(request.getParameter("q"), 150),
                limited(request.getParameter("role"), 50),
                validStatus(request.getParameter("status")),
                positiveInt(request.getParameter("page"), 1)
        );
        try {
            request.setAttribute("userPage", userManagementService.search(criteria));
            request.setAttribute("roles", userManagementService.roles());
            request.setAttribute("criteria", criteria);
            String notice = request.getParameter("notice");
            if ("created".equals(notice)) {
                request.setAttribute("successMessage", "Tài khoản đã được tạo và email mật khẩu tạm đã được gửi.");
            } else if ("updated".equals(notice)) {
                request.setAttribute("successMessage", "Thông tin tài khoản đã được cập nhật.");
            }
            prepareCommon(request);
            request.getRequestDispatcher("/WEB-INF/views/admin/users/list.jsp").forward(request, response);
        } catch (RuntimeException exception) {
            getServletContext().log("User list could not be loaded", exception);
            response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                    "Không thể tải danh sách tài khoản. Vui lòng thử lại.");
        }
    }

    private void prepareCommon(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        request.setAttribute("currentUser", session.getAttribute(SessionKeys.CURRENT_USER));
        request.setAttribute("csrfToken", csrfTokenManager.getOrCreate(session));
    }

    private static String validStatus(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        try {
            return UserStatus.valueOf(value.trim().toUpperCase()).name();
        } catch (IllegalArgumentException exception) {
            return "";
        }
    }

    private static int positiveInt(String value, int fallback) {
        try {
            return Math.max(1, Integer.parseInt(value));
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    private static String limited(String value, int limit) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        return trimmed.length() <= limit ? trimmed : trimmed.substring(0, limit);
    }
}
