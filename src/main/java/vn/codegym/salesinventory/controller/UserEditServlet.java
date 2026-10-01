package vn.codegym.salesinventory.controller;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import vn.codegym.salesinventory.config.ApplicationContextKeys;
import vn.codegym.salesinventory.dto.UserAccountCommand;
import vn.codegym.salesinventory.dto.UserManagementResult;
import vn.codegym.salesinventory.model.ManagedUser;
import vn.codegym.salesinventory.security.CsrfTokenManager;
import vn.codegym.salesinventory.security.CurrentUser;
import vn.codegym.salesinventory.security.SessionKeys;
import vn.codegym.salesinventory.service.UserManagementService;
import vn.codegym.salesinventory.validation.UserAccountValidator;

public final class UserEditServlet extends HttpServlet {
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
        long userId = positiveLong(request.getParameter("id"));
        Optional<ManagedUser> found = userManagementService.find(userId);
        if (found.isEmpty()) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND, "Không tìm thấy tài khoản.");
            return;
        }
        ManagedUser user = found.get();
        request.setAttribute("userId", user.id());
        request.setAttribute("form", new UserAccountCommand(
                user.username(), user.email(), user.fullName(), user.phone(), user.roleCode(), user.status(),
                user.version()));
        render(request, response);
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        HttpSession session = request.getSession(false);
        long userId = positiveLong(request.getParameter("id"));
        long version = nonNegativeLong(request.getParameter("version"));
        UserAccountCommand submitted = UserCreateServlet.commandFrom(request, version);
        ManagedUser existing = userManagementService.find(userId).orElseThrow(() -> new IllegalArgumentException("Không tìm thấy tài khoản."));
        UserAccountCommand command=new UserAccountCommand(submitted.username(),submitted.email(),submitted.fullName(),submitted.phone(),existing.roleCode(),existing.status(),submitted.version());
        request.setAttribute("userId", userId);
        request.setAttribute("form", command);
        if (!csrfTokenManager.isValid(session, request.getParameter("_csrf"))) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            request.setAttribute("formError", "Phiên biểu mẫu không hợp lệ. Vui lòng tải lại trang.");
            render(request, response);
            return;
        }
        Map<String, String> errors = new LinkedHashMap<>(validator.validate(command));
        if (userId <= 0 || !errors.isEmpty()) {
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            request.setAttribute("errors", errors);
            render(request, response);
            return;
        }
        CurrentUser actor = (CurrentUser) session.getAttribute(SessionKeys.CURRENT_USER);
        try {
            UserManagementResult result = userManagementService.update(
                    userId, command, actor.id(), RequestMetadata.authenticationContext(request));
            if (result.status() == UserManagementResult.Status.SUCCESS) {
                RequestMetadata.seeOther(response, request.getContextPath() + "/admin/users?notice=updated");
                return;
            }
            if (result.status() == UserManagementResult.Status.NOT_FOUND) {
                response.sendError(HttpServletResponse.SC_NOT_FOUND, "Không tìm thấy tài khoản.");
                return;
            }
            if (result.status() == UserManagementResult.Status.SELF_PROTECTION) {
                request.setAttribute("formError", "Bạn không thể tự khóa tài khoản hoặc tự gỡ quyền quản trị.");
            } else if (result.status() == UserManagementResult.Status.STALE_UPDATE) {
                request.setAttribute("formError", "Tài khoản vừa được người khác cập nhật. Vui lòng tải lại trước khi sửa.");
            } else {
                UserCreateServlet.applyResult(result, errors, request);
            }
            response.setStatus(result.status() == UserManagementResult.Status.FORBIDDEN
                    ? HttpServletResponse.SC_FORBIDDEN : HttpServletResponse.SC_BAD_REQUEST);
            request.setAttribute("errors", errors);
            render(request, response);
        } catch (RuntimeException exception) {
            getServletContext().log("User could not be updated", exception);
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            request.setAttribute("formError", "Hệ thống chưa thể cập nhật tài khoản. Vui lòng thử lại.");
            render(request, response);
        }
    }

    private void render(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        HttpSession session = request.getSession(false);
        request.setAttribute("roles", userManagementService.roles());
        request.setAttribute("currentUser", session.getAttribute(SessionKeys.CURRENT_USER));
        request.setAttribute("csrfToken", csrfTokenManager.getOrCreate(session));
        request.setAttribute("editing", true);
        request.getRequestDispatcher("/WEB-INF/views/admin/users/form.jsp").forward(request, response);
    }

    private static long positiveLong(String value) {
        long parsed = nonNegativeLong(value);
        return parsed > 0 ? parsed : -1;
    }

    private static long nonNegativeLong(String value) {
        try {
            return Math.max(0, Long.parseLong(value));
        } catch (NumberFormatException | NullPointerException exception) {
            return 0;
        }
    }
}
