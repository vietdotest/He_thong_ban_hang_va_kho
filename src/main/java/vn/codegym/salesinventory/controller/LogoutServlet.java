package vn.codegym.salesinventory.controller;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import vn.codegym.salesinventory.config.ApplicationContextKeys;
import vn.codegym.salesinventory.security.CsrfTokenManager;
import vn.codegym.salesinventory.service.SessionService;

public final class LogoutServlet extends HttpServlet {
    private SessionService sessionService;
    private CsrfTokenManager csrfTokenManager;

    @Override
    public void init() throws ServletException {
        Object sessions = getServletContext().getAttribute(ApplicationContextKeys.SESSION_SERVICE);
        Object csrf = getServletContext().getAttribute(ApplicationContextKeys.CSRF_TOKEN_MANAGER);
        if (!(sessions instanceof SessionService configuredSessions)
                || !(csrf instanceof CsrfTokenManager configuredCsrf)) {
            throw new ServletException("Logout components are not initialized");
        }
        sessionService = configuredSessions;
        csrfTokenManager = configuredCsrf;
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws IOException {
        HttpSession session = request.getSession(false);
        if (!csrfTokenManager.isValid(session, request.getParameter("_csrf"))) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN);
            return;
        }

        sessionService.revoke(session.getId(), "USER_LOGOUT", RequestMetadata.authenticationContext(request));
        session.invalidate();
        Cookie cookie = new Cookie("JSESSIONID", "");
        cookie.setHttpOnly(true);
        cookie.setSecure(request.isSecure());
        cookie.setPath(request.getContextPath().isEmpty() ? "/" : request.getContextPath());
        cookie.setMaxAge(0);
        cookie.setAttribute("SameSite", "Lax");
        response.addCookie(cookie);
        RequestMetadata.seeOther(response, request.getContextPath() + "/login?reason=logged_out");
    }
}
