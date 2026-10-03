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
        vn.codegym.salesinventory.security.Access access=(vn.codegym.salesinventory.security.Access)request.getAttribute("access");
        request.setAttribute("heading",vn.codegym.salesinventory.service.DashboardService.heading(access));
        request.setAttribute("workAreas",vn.codegym.salesinventory.service.DashboardService.areas(access));
        if(access.allows("AUDIT_READ")) {
            javax.sql.DataSource source=(javax.sql.DataSource)getServletContext().getAttribute(ApplicationContextKeys.DATA_SOURCE);
            request.setAttribute("recentOperations",vn.codegym.salesinventory.dao.Sql.transaction(source,c -> vn.codegym.salesinventory.service.AuditService.recent(c,access)));
        }
        request.getRequestDispatcher("/WEB-INF/views/dashboard.jsp").forward(request, response);
    }
}
