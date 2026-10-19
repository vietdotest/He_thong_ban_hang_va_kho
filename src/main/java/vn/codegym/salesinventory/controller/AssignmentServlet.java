package vn.codegym.salesinventory.controller;

import jakarta.servlet.http.*;
import java.util.*;
import vn.codegym.salesinventory.dao.Sql;
import vn.codegym.salesinventory.service.AssignmentService;

/**
 * S1-09: Hiển thị và lưu phân công vai trò, kho, địa bàn cho người dùng.
 */
public final class AssignmentServlet extends PortalServlet {

    // Nạp dữ liệu và hiển thị trang phân công vai trò, kho và địa bàn.
    protected void get(HttpServletRequest request, HttpServletResponse response) throws Exception {
        long id = number(request, "id");
        Sql.transaction(
                source(),
                connection -> {
                    request.setAttribute(
                            "target",
                            Sql.one(
                                    connection,
                                    "SELECT id,username,full_name FROM users WHERE id=?",
                                    id));
                    request.setAttribute(
                            "roles",
                            Sql.query(
                                    connection,
                                    "SELECT r.*,EXISTS(SELECT 1 FROM user_roles ur WHERE"
                                            + " ur.user_id=? AND ur.role_id=r.id) AS selected FROM"
                                            + " roles r ORDER BY r.id",
                                    id));
                    request.setAttribute(
                            "warehouses",
                            Sql.query(
                                    connection,
                                    "SELECT w.*,EXISTS(SELECT 1 FROM user_warehouses uw WHERE"
                                        + " uw.user_id=? AND uw.warehouse_id=w.id) AS selected FROM"
                                        + " warehouses w ORDER BY w.name,w.id",
                                    id));
                    request.setAttribute(
                            "territories",
                            Sql.query(
                                    connection,
                                    "SELECT t.*,EXISTS(SELECT 1 FROM user_territories ut WHERE"
                                        + " ut.user_id=? AND ut.territory_id=t.id) AS selected FROM"
                                        + " territories t ORDER BY t.name,t.id",
                                    id));
                    return null;
                });
        view(request, response, "admin/assignments");
    }

    // Tiếp nhận và xử lý thay đổi phân công vai trò, kho và địa bàn.
    protected void post(HttpServletRequest request, HttpServletResponse response) throws Exception {
        new AssignmentService(source())
                .assign(
                        actor(request).id(),
                        number(request, "id"),
                        new HashSet<>(Arrays.asList(values(request, "role"))),
                        ids(request, "warehouse"),
                        ids(request, "territory"));
        redirect(
                request,
                response,
                "/admin/assignments?id=" + number(request, "id") + "&notice=saved");
    }

    // Hiển thị lại dữ liệu và thông báo khi yêu cầu không hợp lệ.
    protected void badRequest(
            HttpServletRequest request, HttpServletResponse response, String message)
            throws jakarta.servlet.ServletException, java.io.IOException {
        request.setAttribute(
                "form",
                Map.of(
                        "roles",
                        List.of(values(request, "role")),
                        "warehouses",
                        List.of(values(request, "warehouse")),
                        "territories",
                        List.of(values(request, "territory"))));
        request.setAttribute("formError", message);
        response.setStatus(400);
        try {
            get(request, response);
        } catch (IllegalArgumentException invalid) {
            super.badRequest(request, response, message);
        } catch (jakarta.servlet.ServletException | java.io.IOException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new jakarta.servlet.ServletException(failure);
        }
    }

    private static String[] values(HttpServletRequest request, String key) {
        return Optional.ofNullable(request.getParameterValues(key)).orElse(new String[0]);
    }

    // Đọc và kiểm tra danh sách mã được chọn trong biểu mẫu phân công.
    private static Set<Long> ids(HttpServletRequest request, String key) {
        Set<Long> ids = new HashSet<>();
        for (String s : values(request, key)) {
            try {
                ids.add(Long.parseLong(s));
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException("Phạm vi không hợp lệ.");
            }
        }
        return ids;
    }
}
