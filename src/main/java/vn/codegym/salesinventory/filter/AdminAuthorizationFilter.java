package vn.codegym.salesinventory.filter;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import vn.codegym.salesinventory.security.CurrentUser;
import vn.codegym.salesinventory.security.SessionKeys;

public final class AdminAuthorizationFilter implements Filter {
    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest httpRequest = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;
        HttpSession session = httpRequest.getSession(false);
        Object principal = session == null ? null : session.getAttribute(SessionKeys.CURRENT_USER);
        if (!(principal instanceof CurrentUser user) || !user.hasRole("ADMIN")) {
            httpResponse.sendError(HttpServletResponse.SC_FORBIDDEN,
                    "Bạn không có quyền truy cập chức năng quản trị người dùng.");
            return;
        }
        chain.doFilter(request, response);
    }
}
