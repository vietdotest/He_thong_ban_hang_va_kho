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

/**
 * S1-08: Hiển thị danh sách tài khoản có tìm kiếm, lọc và phân trang.
 */
public final class UserListServlet extends HttpServlet {

    private UserManagementService userManagementService;

    private CsrfTokenManager csrfTokenManager;

    // Lấy các thành phần đã khởi tạo để phục vụ yêu cầu HTTP.
    @Override
    public void init() throws ServletException {
        Object service =
                getServletContext().getAttribute(ApplicationContextKeys.USER_MANAGEMENT_SERVICE);
        Object csrf = getServletContext().getAttribute(ApplicationContextKeys.CSRF_TOKEN_MANAGER);
        if (!(service instanceof UserManagementService configuredService)
                || !(csrf instanceof CsrfTokenManager configuredCsrf)) {
            throw new ServletException("User-management components are not initialized");
        }
        userManagementService = configuredService;
        csrfTokenManager = configuredCsrf;
    }

    // Tiếp nhận yêu cầu xem danh sách tài khoản.
    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        UserSearchCriteria criteria =
                new UserSearchCriteria(
                        limited(request.getParameter("q"), 150),
                        limited(request.getParameter("role"), 50),
                        validStatus(request.getParameter("status")),
                        positiveInt(request.getParameter("page"), 1),
                        positiveInt(request.getParameter("pageSize"), 20));
        try {
            var result = userManagementService.search(criteria);
            request.setAttribute("userPage", result);
            request.setAttribute(
                    "pagination",
                    new vn.codegym.salesinventory.dto.PageResult<>(
                            result.items(), result.totalItems(), result.page(), result.pageSize()));
            request.setAttribute("roles", userManagementService.roles());
            request.setAttribute("criteria", criteria);
            String notice = request.getParameter("notice");
            if ("created".equals(notice)) {
                request.setAttribute(
                        "successMessage",
                        "Tài khoản đã được tạo và email mật khẩu tạm đã được gửi.");
            } else if ("updated".equals(notice)) {
                request.setAttribute("successMessage", "Thông tin tài khoản đã được cập nhật.");
            }
            prepareCommon(request);
            request.setAttribute("paginationPath", "/admin/users");
            request.getRequestDispatcher("/WEB-INF/views/admin/users/list.jsp")
                    .forward(request, response);
        } catch (RuntimeException exception) {
            getServletContext().log("User list could not be loaded", exception);
            response.sendError(
                    HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                    "Không thể tải danh sách tài khoản. Vui lòng thử lại.");
        }
    }

    private void prepareCommon(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        request.setAttribute("currentUser", session.getAttribute(SessionKeys.CURRENT_USER));
        request.setAttribute("csrfToken", csrfTokenManager.getOrCreate(session));
    }

    // Kiểm tra trạng thái lọc thuộc danh sách được hỗ trợ.
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

    // Đọc số trang dương hoặc dùng giá trị mặc định.
    private static int positiveInt(String value, int fallback) {
        try {
            return Math.max(1, Integer.parseInt(value));
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    // Giới hạn độ dài từ khóa trước khi tìm tài khoản.
    private static String limited(String value, int limit) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        return trimmed.length() <= limit ? trimmed : trimmed.substring(0, limit);
    }
}
