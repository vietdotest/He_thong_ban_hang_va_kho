package vn.codegym.salesinventory.controller;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import vn.codegym.salesinventory.config.ApplicationContextKeys;
import vn.codegym.salesinventory.dto.UserAccountCommand;
import vn.codegym.salesinventory.dto.UserManagementResult;
import vn.codegym.salesinventory.model.UserStatus;
import vn.codegym.salesinventory.security.CsrfTokenManager;
import vn.codegym.salesinventory.security.CurrentUser;
import vn.codegym.salesinventory.security.SessionKeys;
import vn.codegym.salesinventory.service.UserManagementService;
import vn.codegym.salesinventory.validation.UserAccountValidator;

public final class UserCreateServlet extends HttpServlet {
    private UserManagementService userManagementService;
    private CsrfTokenManager csrfTokenManager;
    private final UserAccountValidator validator = new UserAccountValidator();

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
        request.setAttribute("form", new UserAccountCommand("", "", "", "", "SALES", UserStatus.ACTIVE, 0));
        render(request, response);
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        HttpSession session = request.getSession(false);
        UserAccountCommand command = commandFrom(request, 0);
        request.setAttribute("form", command);
        if (!csrfTokenManager.isValid(session, request.getParameter("_csrf"))) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            request.setAttribute("formError", "Phiên biểu mẫu không hợp lệ. Vui lòng tải lại trang.");
            render(request, response);
            return;
        }
        Map<String, String> errors = new LinkedHashMap<>(validator.validate(command));
        if (command.roleCode().equals("WAREHOUSE") || command.roleCode().equals("WAREHOUSE_MANAGER")) { errors.put("roleCode", "Tạo hồ sơ với vai trò kinh doanh trước, sau đó gán vai trò kho cùng kho phụ trách tại Phân công."); }
        if (!errors.isEmpty()) {
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            request.setAttribute("errors", errors);
            render(request, response);
            return;
        }
        CurrentUser actor = (CurrentUser) session.getAttribute(SessionKeys.CURRENT_USER);
        try {
            UserManagementResult result = userManagementService.create(
                    command, actor.id(), RequestMetadata.authenticationContext(request));
            if (result.status() == UserManagementResult.Status.SUCCESS) {
                RequestMetadata.seeOther(response, request.getContextPath() + "/admin/users?notice=created");
                return;
            }
            applyResult(result, errors, request);
            response.setStatus(result.status() == UserManagementResult.Status.FORBIDDEN
                    ? HttpServletResponse.SC_FORBIDDEN : HttpServletResponse.SC_BAD_REQUEST);
            request.setAttribute("errors", errors);
            render(request, response);
        } catch (RuntimeException exception) {
            getServletContext().log("User could not be created", exception);
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            request.setAttribute("formError", "Hệ thống chưa thể tạo tài khoản. Vui lòng thử lại.");
            render(request, response);
        }
    }

    private void render(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        HttpSession session = request.getSession(false);
        request.setAttribute("roles", userManagementService.roles());
        request.setAttribute("currentUser", session.getAttribute(SessionKeys.CURRENT_USER));
        request.setAttribute("csrfToken", csrfTokenManager.getOrCreate(session));
        request.setAttribute("editing", false);
        request.getRequestDispatcher("/WEB-INF/views/admin/users/form.jsp").forward(request, response);
    }

    static UserAccountCommand commandFrom(HttpServletRequest request, long version) {
        UserStatus status;
        try {
            status = UserStatus.valueOf(request.getParameter("status"));
        } catch (IllegalArgumentException | NullPointerException exception) {
            status = UserStatus.ACTIVE;
        }
        return new UserAccountCommand(
                request.getParameter("username"),
                request.getParameter("email"),
                request.getParameter("fullName"),
                request.getParameter("phone"),
                request.getParameter("roleCode"),
                status,
                version
        );
    }

    static void applyResult(
            UserManagementResult result,
            Map<String, String> errors,
            HttpServletRequest request
    ) {
        switch (result.status()) {
            case DUPLICATE_USERNAME -> errors.put("username", "Tên đăng nhập đã tồn tại.");
            case DUPLICATE_EMAIL -> errors.put("email", "Email đã được sử dụng.");
            case DUPLICATE_PHONE -> errors.put("phone", "Số điện thoại đã được sử dụng.");
            case INVALID_ROLE -> errors.put("roleCode", "Vai trò đã chọn không hợp lệ.");
            case EMAIL_DELIVERY_FAILED -> request.setAttribute("formError",
                    "Không gửi được email mật khẩu tạm nên tài khoản chưa được tạo. Vui lòng kiểm tra dịch vụ email.");
            case FORBIDDEN -> request.setAttribute("formError", "Bạn không có quyền thực hiện thao tác này.");
            default -> request.setAttribute("formError", "Không thể lưu tài khoản. Vui lòng tải lại trang và thử lại.");
        }
    }
}
