package vn.codegym.salesinventory.controller;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.*;
import javax.sql.DataSource;
import vn.codegym.salesinventory.config.ApplicationContextKeys;
import vn.codegym.salesinventory.security.*;

public abstract class PortalServlet extends HttpServlet {
    protected DataSource source() { return (DataSource)getServletContext().getAttribute(ApplicationContextKeys.DATA_SOURCE); }
    protected CurrentUser actor(HttpServletRequest r) { return (CurrentUser)r.getSession(false).getAttribute(SessionKeys.CURRENT_USER); }
    protected Access access(HttpServletRequest r) { return (Access)r.getAttribute("access"); }
    protected static String value(HttpServletRequest r,String key) { String v=r.getParameter(key); return v==null ? "" : v.trim(); }
    protected static long number(HttpServletRequest r,String key) { try { return Long.parseLong(value(r,key)); } catch(NumberFormatException e) { throw new IllegalArgumentException("Mã bản ghi không hợp lệ."); } }
    protected void view(HttpServletRequest r,HttpServletResponse s,String path) throws ServletException,IOException { r.getRequestDispatcher("/WEB-INF/views/"+path+".jsp").forward(r,s); }
    protected void redirect(HttpServletRequest r,HttpServletResponse s,String path) { s.setStatus(303); s.setHeader("Location",r.getContextPath()+path); }
    protected void get(HttpServletRequest r,HttpServletResponse s) throws Exception { s.sendError(405); }
    protected void post(HttpServletRequest r,HttpServletResponse s) throws Exception { s.sendError(405); }
    protected void preparePost(HttpServletRequest r) throws Exception { }
    protected void badRequest(HttpServletRequest r,HttpServletResponse s,String message) throws ServletException,IOException {
        s.setStatus(400); r.setAttribute("message",message); r.setAttribute("returnPath",r.getRequestURI()); view(r,s,"message");
    }
    @Override protected final void doGet(HttpServletRequest r,HttpServletResponse s) throws ServletException,IOException { dispatch(r,s,false); }
    @Override protected final void doPost(HttpServletRequest r,HttpServletResponse s) throws ServletException,IOException { dispatch(r,s,true); }
    private void dispatch(HttpServletRequest r,HttpServletResponse s,boolean post) throws ServletException,IOException {
        try {
            if(post) preparePost(r);
            if(post && !((CsrfTokenManager)getServletContext().getAttribute(ApplicationContextKeys.CSRF_TOKEN_MANAGER)).isValid(r.getSession(false),r.getParameter("_csrf"))) { s.sendError(403); return; }
            if(post) post(r,s); else get(r,s);
        } catch(SecurityException e) { s.sendError(403); }
        catch(IllegalArgumentException e) { badRequest(r,s,e.getMessage()); }
        catch(Exception e) { getServletContext().log("Không thể xử lý yêu cầu",e); s.sendError(500); }
    }
}
