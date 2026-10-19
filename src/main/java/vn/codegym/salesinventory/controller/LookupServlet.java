package vn.codegym.salesinventory.controller;

import jakarta.servlet.http.*;
import java.util.Map;
import vn.codegym.salesinventory.service.*;

/**
 * Dùng chung: Trả dữ liệu gợi ý tìm kiếm trong phạm vi người dùng được phép truy cập.
 */
public final class LookupServlet extends PortalServlet {

    // Trả danh sách gợi ý tìm kiếm dưới dạng JSON theo quyền của người dùng.
    @Override
    protected void get(HttpServletRequest request, HttpServletResponse response) throws Exception {
        String path = request.getPathInfo();
        if (path == null || !path.matches("/[a-z]+")) {
            response.sendError(404);
            return;
        }
        var rows =
                "/unitnames".equals(path)
                        ? new UnitService(source())
                                .suggestNames(
                                        actor(request).id(),
                                        number(request, "product"),
                                        value(request, "q"))
                        : new LookupService(source())
                                .search(
                                        actor(request).id(),
                                        path.substring(1),
                                        value(request, "q"));
        response.setContentType("application/json;charset=UTF-8");
        response.setHeader("Cache-Control", "no-store");
        response.getWriter().write(AuditService.snapshot(Map.of("items", rows), false));
    }
}
