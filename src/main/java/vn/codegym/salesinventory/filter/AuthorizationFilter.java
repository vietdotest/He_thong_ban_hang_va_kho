package vn.codegym.salesinventory.filter;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import javax.sql.DataSource;
import vn.codegym.salesinventory.config.ApplicationContextKeys;
import vn.codegym.salesinventory.security.*;
import vn.codegym.salesinventory.service.AccessService;

/**
 * S1-05, S1-06, S1-07: Nạp quyền, kiểm đường dẫn và trả lỗi 403 khi không đủ quyền.
 */
public final class AuthorizationFilter implements Filter {

    private AccessService service;

    public void init(FilterConfig config) {
        service =
                new AccessService(
                        (DataSource)
                                config.getServletContext()
                                        .getAttribute(ApplicationContextKeys.DATA_SOURCE));
    }

    // Nạp quyền và phạm vi, chặn đường dẫn không được phép truy cập.
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest r = (HttpServletRequest) request;
        HttpServletResponse s = (HttpServletResponse) response;
        String path = r.getRequestURI().substring(r.getContextPath().length());
        if (RoutePermissions.publicPath(path)) {
            chain.doFilter(request, response);
            return;
        }
        boolean actionRoute =
                java.util.Set.of(
                                "/orders",
                                "/accounting/returns",
                                "/fulfillment/shipments",
                                "/inventory/receipts")
                        .contains(path);
        String[] actions = actionRoute ? r.getParameterValues("action") : null;
        if ("POST".equals(r.getMethod()) && actions != null && actions.length != 1) {
            s.sendError(400);
            return;
        }
        String required =
                RoutePermissions.required(
                        path,
                        r.getMethod(),
                        actionRoute && "POST".equals(r.getMethod())
                                ? r.getParameter("action")
                                : null);
        if (required == null) {
            s.sendError(404);
            return;
        }
        Object principal =
                r.getSession(false) == null
                        ? null
                        : r.getSession(false).getAttribute(SessionKeys.CURRENT_USER);
        if (!(principal instanceof CurrentUser user)) {
            s.sendError(403);
            return;
        }
        Access access = service.load(user.id());
        if (access.roles().contains("DEALER")) {
            if (!access.roles().equals(java.util.Set.of("DEALER"))) {
                s.sendError(403);
                return;
            }
            if (path.equals("/dashboard")) {
                s.setStatus(303);
                s.setHeader("Location", r.getContextPath() + "/portal");
                return;
            }
            if (!(path.equals("/portal")
                    || path.equals("/portal/orders")
                    || path.startsWith("/api/portal/")
                    || path.startsWith("/account/")
                    || path.equals("/logout"))) {
                s.sendError(403);
                return;
            }
        }
        if (!access.allows(required)) {
            s.sendError(403);
            return;
        }
        r.setAttribute("access", access);
        r.setAttribute("currentUser", user);
        r.setAttribute("canCost", access.allows("COST_READ"));
        r.setAttribute(
                "csrfToken",
                ((CsrfTokenManager)
                                r.getServletContext()
                                        .getAttribute(ApplicationContextKeys.CSRF_TOKEN_MANAGER))
                        .getOrCreate(r.getSession(false)));
        chain.doFilter(request, response);
    }
}
