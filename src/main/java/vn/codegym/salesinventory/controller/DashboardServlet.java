package vn.codegym.salesinventory.controller;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import vn.codegym.salesinventory.config.ApplicationContextKeys;
import vn.codegym.salesinventory.security.CsrfTokenManager;
import vn.codegym.salesinventory.security.SessionKeys;

public final class DashboardServlet extends HttpServlet {
    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        request.setAttribute("currentUser", request.getSession(false).getAttribute(SessionKeys.CURRENT_USER));
        CsrfTokenManager csrf = (CsrfTokenManager) getServletContext()
                .getAttribute(ApplicationContextKeys.CSRF_TOKEN_MANAGER);
        request.setAttribute("csrfToken", csrf.getOrCreate(request.getSession(false)));
        request.getRequestDispatcher("/WEB-INF/views/dashboard.jsp").forward(request, response);
    }
}
