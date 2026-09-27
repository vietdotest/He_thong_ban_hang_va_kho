package vn.codegym.salesinventory.controller;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.util.Map;
import vn.codegym.salesinventory.config.ApplicationContextKeys;
import vn.codegym.salesinventory.dto.AuthenticationContext;
import vn.codegym.salesinventory.dto.AuthenticationResult;
import vn.codegym.salesinventory.dto.LoginRequest;
import vn.codegym.salesinventory.exception.AuthenticationException;
import vn.codegym.salesinventory.security.CsrfTokenManager;
import vn.codegym.salesinventory.security.SessionKeys;
import vn.codegym.salesinventory.service.AuthenticationService;
import vn.codegym.salesinventory.service.SessionService;
import vn.codegym.salesinventory.validation.LoginValidator;

public final class LoginServlet extends HttpServlet {
    static final String GENERIC_AUTHENTICATION_ERROR =
            "Thông tin đăng nhập không chính xác hoặc tài khoản hiện không thể đăng nhập.";
    private AuthenticationService authenticationService;
    private SessionService sessionService;
    private CsrfTokenManager csrfTokenManager;
    private LoginValidator loginValidator;

    public LoginServlet() {
        this.loginValidator = new LoginValidator();
    }

    LoginServlet(
            AuthenticationService authenticationService,
            SessionService sessionService,
            CsrfTokenManager csrfTokenManager,
            LoginValidator loginValidator
    ) {
        this.authenticationService = authenticationService;
        this.sessionService = sessionService;
        this.csrfTokenManager = csrfTokenManager;
        this.loginValidator = loginValidator;
    }

    @Override
    public void init() throws ServletException {
        if (authenticationService != null && sessionService != null && csrfTokenManager != null) {
            return;
        }
        Object service = getServletContext().getAttribute(ApplicationContextKeys.AUTHENTICATION_SERVICE);
        Object configuredSessionService = getServletContext().getAttribute(ApplicationContextKeys.SESSION_SERVICE);
        Object csrfManager = getServletContext().getAttribute(ApplicationContextKeys.CSRF_TOKEN_MANAGER);
        if (!(service instanceof AuthenticationService configuredService)
                || !(configuredSessionService instanceof SessionService sessionManager)
                || !(csrfManager instanceof CsrfTokenManager configuredCsrfManager)) {
            throw new ServletException("Authentication components are not initialized");
        }
        this.authenticationService = configuredService;
        this.sessionService = sessionManager;
        this.csrfTokenManager = configuredCsrfManager;
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        HttpSession existingSession = request.getSession(false);
        if (existingSession != null && existingSession.getAttribute(SessionKeys.CURRENT_USER) != null) {
            if (sessionService.validate(existingSession.getId()).valid()) {
                RequestMetadata.seeOther(response, request.getContextPath() + "/dashboard");
                return;
            }
            existingSession.invalidate();
        }
        String reason = request.getParameter("reason");
        if ("logged_out".equals(reason)) {
            request.setAttribute("notice", "Bạn đã đăng xuất.");
        } else if ("session_expired".equals(reason)) {
            request.setAttribute("notice", "Phiên làm việc đã hết hạn. Vui lòng đăng nhập lại.");
        } else if ("password_reset".equals(reason)) {
            request.setAttribute("notice", "Mật khẩu đã được cập nhật. Bạn có thể đăng nhập lại.");
        }
        prepareCsrf(request);
        renderLogin(request, response);
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        HttpSession session = request.getSession(false);
        String identity = request.getParameter("identity");
        request.setAttribute("identity", safeIdentityForDisplay(identity));

        if (!csrfTokenManager.isValid(session, request.getParameter("_csrf"))) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            request.setAttribute("formError", "Phiên biểu mẫu không hợp lệ. Vui lòng tải lại trang và thử lại.");
            prepareCsrf(request);
            renderLogin(request, response);
            return;
        }

        LoginRequest loginRequest = new LoginRequest(identity, request.getParameter("password"));
        Map<String, String> errors = loginValidator.validate(loginRequest);
        if (!errors.isEmpty()) {
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            request.setAttribute("fieldErrors", errors);
            request.setAttribute("identityError", errors.get("identity"));
            request.setAttribute("passwordError", errors.get("password"));
            prepareCsrf(request);
            renderLogin(request, response);
            return;
        }

        try {
            AuthenticationResult result = authenticationService.authenticate(
                    loginRequest,
                    RequestMetadata.authenticationContext(request)
            );
            if (!result.authenticated()) {
                request.setAttribute("formError", GENERIC_AUTHENTICATION_ERROR);
                prepareCsrf(request);
                renderLogin(request, response);
                return;
            }

            String newSessionId = request.changeSessionId();
            session.setAttribute(SessionKeys.CURRENT_USER, result.currentUser());
            csrfTokenManager.rotate(session);
            String serverSessionId = sessionService.create(
                    result.currentUser(), newSessionId, RequestMetadata.authenticationContext(request));
            session.setAttribute(SessionKeys.SERVER_SESSION_ID, serverSessionId);
            RequestMetadata.seeOther(response, request.getContextPath() + "/dashboard");
        } catch (AuthenticationException exception) {
            getServletContext().log("Login could not be processed", exception);
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            request.setAttribute("formError", "Hệ thống tạm thời không thể xử lý đăng nhập. Vui lòng thử lại sau.");
            prepareCsrf(request);
            renderLogin(request, response);
        }
    }

    private void prepareCsrf(HttpServletRequest request) {
        request.setAttribute("csrfToken", csrfTokenManager.getOrCreate(request.getSession(true)));
    }

    private void renderLogin(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        request.getRequestDispatcher("/WEB-INF/views/auth/login.jsp").forward(request, response);
    }

    private static String safeIdentityForDisplay(String identity) {
        if (identity == null) {
            return "";
        }
        String trimmed = identity.trim();
        return trimmed.length() <= LoginValidator.MAX_IDENTITY_LENGTH
                ? trimmed
                : trimmed.substring(0, LoginValidator.MAX_IDENTITY_LENGTH);
    }
}
