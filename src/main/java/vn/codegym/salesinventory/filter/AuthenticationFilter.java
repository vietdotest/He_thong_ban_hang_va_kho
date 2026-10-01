package vn.codegym.salesinventory.filter;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import vn.codegym.salesinventory.config.ApplicationContextKeys;
import vn.codegym.salesinventory.dto.SessionValidationResult;
import vn.codegym.salesinventory.security.CurrentUser;
import vn.codegym.salesinventory.security.SessionKeys;
import vn.codegym.salesinventory.service.SessionService;

public final class AuthenticationFilter implements Filter {
    private SessionService sessionService;

    public AuthenticationFilter() {
    }

    AuthenticationFilter(SessionService sessionService) {
        this.sessionService = sessionService;
    }

    @Override
    public void init(FilterConfig filterConfig) throws ServletException {
        if (sessionService != null) {
            return;
        }
        Object configured = filterConfig.getServletContext().getAttribute(ApplicationContextKeys.SESSION_SERVICE);
        if (!(configured instanceof SessionService configuredService)) {
            throw new ServletException("Session service is not initialized");
        }
        sessionService = configuredService;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest httpRequest = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;
        String path = httpRequest.getRequestURI() == null ? "" : httpRequest.getRequestURI().substring(httpRequest.getContextPath().length());
        if (vn.codegym.salesinventory.security.RoutePermissions.publicPath(path)) { chain.doFilter(request,response); return; }
        HttpSession session = httpRequest.getSession(false);
        Object currentUser = session == null ? null : session.getAttribute(SessionKeys.CURRENT_USER);

        if (!(currentUser instanceof CurrentUser) || sessionService == null) {
            redirectToLogin(httpRequest, httpResponse, false);
            return;
        }

        SessionValidationResult validation;
        try {
            validation = sessionService.validate(session.getId());
        } catch (RuntimeException exception) {
            throw new ServletException("Session validation failed", exception);
        }
        if (!validation.valid()) {
            session.invalidate();
            redirectToLogin(httpRequest, httpResponse, true);
            return;
        }
        CurrentUser refreshedUser = validation.currentUser();
        session.setAttribute(SessionKeys.CURRENT_USER, refreshedUser);
        session.setAttribute(SessionKeys.SERVER_SESSION_ID, validation.serverSessionId());

        httpResponse.setHeader("Cache-Control", "no-store, no-cache, must-revalidate, max-age=0");
        httpResponse.setHeader("Pragma", "no-cache");
        String requestPath = httpRequest.getRequestURI();
        if (refreshedUser.mustChangePassword()
                && !requestPath.equals(httpRequest.getContextPath() + "/account/change-password")
                && !requestPath.equals(httpRequest.getContextPath() + "/logout")) {
            httpResponse.setStatus(HttpServletResponse.SC_SEE_OTHER);
            httpResponse.setHeader("Location", httpResponse.encodeRedirectURL(
                    httpRequest.getContextPath() + "/account/change-password?required=true"));
            return;
        }
        chain.doFilter(request, response);
    }

    private static void redirectToLogin(
            HttpServletRequest request,
            HttpServletResponse response,
            boolean expired
    ) {
        String location = request.getContextPath() + "/login" + (expired ? "?reason=session_expired" : "");
        response.setStatus(HttpServletResponse.SC_SEE_OTHER);
        response.setHeader("Location", response.encodeRedirectURL(location));
    }
}
