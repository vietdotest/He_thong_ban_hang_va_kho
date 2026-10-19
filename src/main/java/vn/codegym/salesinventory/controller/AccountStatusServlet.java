package vn.codegym.salesinventory.controller;

import jakarta.servlet.http.*;
import vn.codegym.salesinventory.dao.Sql;
import vn.codegym.salesinventory.service.AccountStatusService;

/**
 * S1-10: Nhận yêu cầu khóa, mở tài khoản và hiển thị lỗi nhập liệu.
 */
public final class AccountStatusServlet extends PortalServlet {

    // Nạp dữ liệu và hiển thị trang trạng thái tài khoản.
    protected void get(HttpServletRequest request, HttpServletResponse response) throws Exception {
        long id = number(request, "id");
        Sql.transaction(
                source(),
                connection -> {
                    request.setAttribute(
                            "target",
                            Sql.one(
                                    connection,
                                    "SELECT id,username,full_name,status,lock_reason FROM users"
                                            + " WHERE id=?",
                                    id));
                    request.setAttribute(
                            "assignmentCount",
                            Sql.id(
                                    Sql.one(
                                                    connection,
                                                    "SELECT COUNT(*) total FROM dealers WHERE"
                                                            + " primary_staff_id=?",
                                                    id)
                                            .get("total")));
                    request.setAttribute(
                            "warnings",
                            Sql.query(
                                    connection,
                                    "SELECT d.id,d.code,d.name,COALESCE(MAX(h.reason),'Nhân viên"
                                        + " vẫn phụ trách đại lý này.') reason FROM dealers d LEFT"
                                        + " JOIN handover_warnings h ON h.user_id=? AND"
                                        + " h.dealer_reference=CONCAT('DEALER:',d.id) AND"
                                        + " h.resolved_at IS NULL WHERE d.primary_staff_id=? GROUP"
                                        + " BY d.id,d.code,d.name ORDER BY d.code,d.id LIMIT 20",
                                    id,
                                    id));
                    return null;
                });
        request.setAttribute(
                "canHandover",
                access(request).allows("DEALER_HANDOVER")
                        && access(request).roles().contains("SALES_MANAGER")
                        && vn.codegym.salesinventory.service.DealerService.all(access(request)));
        view(request, response, "admin/users/status");
    }

    // Tiếp nhận và xử lý thay đổi trạng thái tài khoản.
    protected void post(HttpServletRequest request, HttpServletResponse response) {
        String action = value(request, "action");
        if (!action.equals("lock") && !action.equals("unlock")) {
            throw new IllegalArgumentException("Thao tác không hợp lệ.");
        }
        new AccountStatusService(source())
                .change(
                        actor(request).id(),
                        number(request, "id"),
                        action.equals("lock"),
                        value(request, "reason"));
        redirect(
                request,
                response,
                "/admin/users/status?id=" + number(request, "id") + "&notice=saved");
    }

    // Hiển thị lại dữ liệu và thông báo khi yêu cầu không hợp lệ.
    protected void badRequest(
            HttpServletRequest request, HttpServletResponse response, String message)
            throws jakarta.servlet.ServletException, java.io.IOException {
        request.setAttribute("formError", message);
        request.setAttribute("reason", value(request, "reason"));
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
}
