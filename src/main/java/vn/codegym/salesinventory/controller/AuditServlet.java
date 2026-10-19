package vn.codegym.salesinventory.controller;

import jakarta.servlet.http.*;
import java.time.*;
import java.sql.Timestamp;
import java.util.*;
import vn.codegym.salesinventory.dao.Sql;
import vn.codegym.salesinventory.dto.*;
import vn.codegym.salesinventory.service.AuditReadService;

/**
 * S2-04: Hiển thị nhật ký theo người thao tác, đối tượng và khoảng thời gian.
 */
public final class AuditServlet extends PortalServlet {

    // Chứa điều kiện SQL, tham số lọc và số trang nhật ký.
    record Filter(String sql, List<Object> args, int page) {}

    // Tạo điều kiện lọc nhật ký theo người thực hiện, đối tượng, khoảng ngày và trang.
    static Filter filter(String user, String type, String from, String to, String page) {
        StringBuilder sql = new StringBuilder(" WHERE 1=1 ");
        List<Object> parameters = new ArrayList<>();
        if (!user.isBlank()) {
            long id;
            try {
                id = Long.parseLong(user);
            } catch (NumberFormatException invalid) {
                throw new IllegalArgumentException("Người thực hiện không hợp lệ.");
            }
            if (id <= 0) {
                throw new IllegalArgumentException("Người thực hiện không hợp lệ.");
            }
            sql.append(" AND a.actor_user_id=?");
            parameters.add(id);
        }
        if (!type.isBlank()) {
            sql.append(" AND a.object_type=?");
            parameters.add(type);
        }
        try {
            LocalDate start = from.isBlank() ? null : LocalDate.parse(from),
                    end = to.isBlank() ? null : LocalDate.parse(to);
            if (start != null && end != null && end.isBefore(start)) {
                throw new IllegalArgumentException("Đến ngày phải từ ngày bắt đầu trở đi.");
            }
            if (start != null) {
                sql.append(" AND a.occurred_at>=?");
                parameters.add(
                        Timestamp.from(
                                start.atStartOfDay(ZoneId.of("Asia/Ho_Chi_Minh")).toInstant()));
            }
            // Lấy trước đầu ngày kế tiếp để bao gồm toàn bộ ngày kết thúc.
            if (end != null) {
                sql.append(" AND a.occurred_at<?");
                parameters.add(
                        Timestamp.from(
                                end.plusDays(1)
                                        .atStartOfDay(ZoneId.of("Asia/Ho_Chi_Minh"))
                                        .toInstant()));
            }
        } catch (java.time.DateTimeException invalid) {
            throw new IllegalArgumentException("Khoảng ngày không hợp lệ.");
        }
        long selected;
        try {
            selected = page.isBlank() ? 1 : Long.parseLong(page);
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException("Trang không hợp lệ.");
        }
        if (selected < 1 || selected > 100000) {
            throw new IllegalArgumentException("Trang không hợp lệ.");
        }
        return new Filter(sql.toString(), List.copyOf(parameters), (int) selected);
    }

    // Nạp dữ liệu và hiển thị trang nhật ký thao tác.
    protected void get(HttpServletRequest request, HttpServletResponse response) throws Exception {
        access(request).require("AUDIT_READ");
        Filter filter;
        try {
            filter =
                    filter(
                            value(request, "userId"),
                            value(request, "type"),
                            value(request, "from"),
                            value(request, "to"),
                            "1");
        } catch (IllegalArgumentException invalid) {
            response.setStatus(400);
            request.setAttribute("filterError", invalid.getMessage());
            request.setAttribute("logs", List.of());
            request.setAttribute("pageNumber", 1);
            request.setAttribute("pagination", new PageResult<>(List.of(), 0, 1, 20));
            options(request);
            view(request, response, "admin/audit");
            return;
        }
        var page =
                new AuditReadService(source())
                        .search(
                                actor(request).id(),
                                filter.sql(),
                                filter.args().toArray(),
                                value(request, "q"),
                                PageRequest.parse(
                                        value(request, "page"), value(request, "pageSize")));
        request.setAttribute("logs", page.items());
        request.setAttribute("pagination", page);
        options(request);
        request.setAttribute("pageNumber", page.page());
        view(request, response, "admin/audit");
    }

    private void options(HttpServletRequest request) {
        var options =
                new AuditReadService(source())
                        .options(actor(request).id(), value(request, "userId"));
        request.setAttribute("users", options.get("users"));
        request.setAttribute("types", options.get("types"));
    }
}
