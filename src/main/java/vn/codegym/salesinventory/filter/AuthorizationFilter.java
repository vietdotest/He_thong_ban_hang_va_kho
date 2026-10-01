package vn.codegym.salesinventory.filter;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import javax.sql.DataSource;
import vn.codegym.salesinventory.config.ApplicationContextKeys;
import vn.codegym.salesinventory.security.*;
import vn.codegym.salesinventory.service.AccessService;

public final class AuthorizationFilter implements Filter {
    private AccessService service;
    public void init(FilterConfig config) { service=new AccessService((DataSource)config.getServletContext().getAttribute(ApplicationContextKeys.DATA_SOURCE)); }
    public void doFilter(ServletRequest req,ServletResponse res,FilterChain chain) throws IOException,ServletException {
        HttpServletRequest r=(HttpServletRequest)req; HttpServletResponse s=(HttpServletResponse)res;
        String path=r.getRequestURI().substring(r.getContextPath().length());
        if(RoutePermissions.publicPath(path)) { chain.doFilter(req,res); return; }
        String required=RoutePermissions.required(path,r.getMethod());
        if(required==null) { s.sendError(404); return; }
        Object principal=r.getSession(false)==null ? null : r.getSession(false).getAttribute(SessionKeys.CURRENT_USER);
        if(!(principal instanceof CurrentUser user)) { s.sendError(403); return; }
        Access access=service.load(user.id());
        if(!access.allows(required)) { s.sendError(403); return; }
        r.setAttribute("access",access); r.setAttribute("currentUser",user);
        r.setAttribute("canCost",access.allows("COST_READ"));
        r.setAttribute("csrfToken",((CsrfTokenManager)r.getServletContext().getAttribute(ApplicationContextKeys.CSRF_TOKEN_MANAGER)).getOrCreate(r.getSession(false)));
        chain.doFilter(req,res);
    }
}
