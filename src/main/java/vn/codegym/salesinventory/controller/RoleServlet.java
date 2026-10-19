package vn.codegym.salesinventory.controller;

import jakarta.servlet.http.*;
import java.util.*;
import vn.codegym.salesinventory.dao.Sql;

/**
 * S1-05: Hiển thị và lưu danh sách quyền của từng vai trò.
 */
public final class RoleServlet extends PortalServlet {

    // Nạp dữ liệu và hiển thị trang quyền của vai trò.
    protected void get(HttpServletRequest request, HttpServletResponse response) throws Exception {
        Sql.transaction(
                source(),
                connection -> {
                    request.setAttribute(
                            "roles", Sql.query(connection, "SELECT * FROM roles ORDER BY id"));
                    request.setAttribute(
                            "permissions",
                            Sql.query(connection, "SELECT * FROM permissions ORDER BY code"));
                    request.setAttribute(
                            "grants",
                            Sql.query(
                                    connection,
                                    "SELECT role_id,permission_id FROM role_permissions"));
                    return null;
                });
        view(request, response, "admin/roles");
    }

    // Tiếp nhận và xử lý thay đổi quyền của vai trò.
    protected void post(HttpServletRequest request, HttpServletResponse response) throws Exception {
        access(request).require("ROLE_MANAGE");
        long roleId = number(request, "roleId");
        Set<String> chosen =
                new HashSet<>(
                        Arrays.asList(
                                Optional.ofNullable(request.getParameterValues("permission"))
                                        .orElse(new String[0])));
        Sql.transaction(
                source(),
                connection -> {
                    var role =
                            Sql.one(
                                    connection,
                                    "SELECT code FROM roles WHERE id=? FOR UPDATE",
                                    roleId);
                    if ("DEALER".equals(role.get("code"))
                            && !Set.of("PROFILE", "PORTAL_ORDER_READ", "PORTAL_ORDER_WRITE")
                                    .containsAll(chosen)) {
                        throw new IllegalArgumentException(
                                "Vai trò đại lý không được cấp quyền nội bộ.");
                    }
                    if (Sql.text(role.get("code")).equals("ADMIN")
                            && (!chosen.contains("USER_MANAGE")
                                    || !chosen.contains("ROLE_MANAGE")
                                    || !chosen.contains("PROFILE"))) {
                        throw new IllegalArgumentException(
                                "Phải giữ quyền quản trị và hồ sơ của vai trò quản trị hệ thống.");
                    }
                    if (!Sql.text(role.get("code")).equals("SALES_MANAGER")
                            && (chosen.contains("COST_READ") || chosen.contains("COST_WRITE"))) {
                        throw new IllegalArgumentException(
                                "Giá vốn chỉ dành cho Quản lý kinh doanh.");
                    }
                    for (String p : chosen) {
                        Sql.one(connection, "SELECT id FROM permissions WHERE code=?", p);
                    }
                    var before =
                            Sql.query(
                                    connection,
                                    "SELECT p.code FROM permissions p JOIN role_permissions rp ON"
                                            + " rp.permission_id=p.id WHERE rp.role_id=?",
                                    roleId);
                    Sql.update(connection, "DELETE FROM role_permissions WHERE role_id=?", roleId);
                    for (String p : chosen) {
                        Sql.update(
                                connection,
                                "INSERT INTO role_permissions(role_id,permission_id) SELECT ?,id"
                                        + " FROM permissions WHERE code=?",
                                roleId,
                                p);
                    }
                    vn.codegym.salesinventory.service.AuditService.record(
                            connection,
                            actor(request).id(),
                            "ROLE_PERMISSIONS_UPDATED",
                            "ROLE",
                            roleId,
                            Map.of("permissions", before.toString()),
                            Map.of("permissions", chosen.toString()));
                    return null;
                });
        redirect(request, response, "/admin/roles?notice=saved");
    }

    // Hiển thị lại dữ liệu và thông báo khi yêu cầu không hợp lệ.
    protected void badRequest(
            HttpServletRequest request, HttpServletResponse response, String message)
            throws jakarta.servlet.ServletException, java.io.IOException {
        request.setAttribute("errorRole", value(request, "roleId"));
        request.setAttribute(
                "chosenPermissions",
                List.of(
                        Optional.ofNullable(request.getParameterValues("permission"))
                                .orElse(new String[0])));
        request.setAttribute("formError", message);
        response.setStatus(400);
        try {
            get(request, response);
        } catch (jakarta.servlet.ServletException | java.io.IOException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new jakarta.servlet.ServletException(failure);
        }
    }
}
