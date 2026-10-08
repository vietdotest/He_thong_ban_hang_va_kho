package vn.codegym.salesinventory.controller;

import jakarta.servlet.http.*;
import java.util.Map;
import vn.codegym.salesinventory.service.*;

public final class LookupServlet extends PortalServlet {
    @Override protected void get(HttpServletRequest request, HttpServletResponse response) throws Exception {
        String path = request.getPathInfo();
        if (path == null || !path.matches("/[a-z]+")) { response.sendError(404); return; }
        var rows = new LookupService(source()).search(actor(request).id(), path.substring(1), value(request,"q"));
        response.setContentType("application/json;charset=UTF-8");
        response.setHeader("Cache-Control","no-store");
        response.getWriter().write(AuditService.snapshot(Map.of("items",rows),false));
    }
}
