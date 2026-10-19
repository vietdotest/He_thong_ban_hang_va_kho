package vn.codegym.salesinventory.controller;

import jakarta.servlet.http.*;
import java.util.*;
import vn.codegym.salesinventory.service.ScopeService;
import vn.codegym.salesinventory.dto.PageRequest;

/**
 * S1-09, S5-03 (phần danh mục kho): Hiển thị và lưu danh mục kho, địa bàn để phân công.
 */
public final class ScopeServlet extends PortalServlet {

    private ScopeService service() {
        return new ScopeService(source());
    }

    // Nạp dữ liệu và hiển thị trang danh mục kho và địa bàn.
    protected void get(HttpServletRequest request, HttpServletResponse response) throws Exception {
        for (String kind : List.of("warehouse", "territory")) {
            var page =
                    service()
                            .search(
                                    actor(request).id(),
                                    kind,
                                    value(request, "q"),
                                    PageRequest.parse(
                                            value(request, kind + "Page"),
                                            value(request, kind + "PageSize")));
            request.setAttribute(kind + "Pagination", page);
            request.setAttribute(
                    kind.equals("warehouse") ? "warehouses" : "territories", page.items());
        }
        request.setAttribute("scopePaging", paging(request));
        request.setAttribute("scopeReturn", returnPath(request));
        if (request.getAttribute("form") == null) {
            String kind = value(request, "kind").equals("territory") ? "territory" : "warehouse";
            if (!value(request, "edit").isEmpty()) {
                var form =
                        new LinkedHashMap<>(
                                service().find(actor(request).id(), kind, number(request, "edit")));
                form.put("kind", kind);
                request.setAttribute("form", form);
            } else {
                request.setAttribute("form", Map.of("kind", kind));
            }
        }
        view(request, response, "admin/scopes");
    }

    // Tiếp nhận và xử lý thay đổi danh mục kho và địa bàn.
    protected void post(HttpServletRequest request, HttpServletResponse response) throws Exception {
        try {
            service()
                    .save(
                            actor(request).id(),
                            value(request, "kind"),
                            value(request, "id").isEmpty() ? 0 : number(request, "id"),
                            value(request, "code"),
                            value(request, "name"),
                            value(request, "address"),
                            value(request, "version").isEmpty() ? 0 : number(request, "version"));
        } catch (vn.codegym.salesinventory.validation.FieldValidationException invalid) {
            request.setAttribute("errors", invalid.errors());
            throw invalid;
        }
        redirect(request, response, returnPath(request) + "&notice=saved");
    }

    // Đọc và kiểm tra tham số phân trang của danh mục kho, địa bàn.
    private static Map<String, String> paging(HttpServletRequest request) {
        var params = new LinkedHashMap<String, String>();
        for (String kind : List.of("warehouse", "territory")) {
            var page =
                    PageRequest.parse(
                            value(request, kind + "Page"), value(request, kind + "PageSize"));
            params.put(kind + "Page", Integer.toString(page.page()));
            params.put(kind + "PageSize", Integer.toString(page.pageSize()));
        }
        return params;
    }

    // Tạo địa chỉ trở về đúng danh mục, bộ lọc và trang đang xem.
    private static String returnPath(HttpServletRequest request) {
        var params = paging(request);
        params.put("q", value(request, "q"));
        return "/admin/scopes?"
                + params.entrySet().stream()
                        .map(
                                p ->
                                        p.getKey()
                                                + "="
                                                + java.net.URLEncoder.encode(
                                                        p.getValue(),
                                                        java.nio.charset.StandardCharsets.UTF_8))
                        .collect(java.util.stream.Collectors.joining("&"));
    }

    // Hiển thị lại dữ liệu và thông báo khi yêu cầu không hợp lệ.
    protected void badRequest(
            HttpServletRequest request, HttpServletResponse response, String message)
            throws jakarta.servlet.ServletException, java.io.IOException {
        if (!request.getMethod().equals("POST")) {
            super.badRequest(request, response, message);
            return;
        }
        request.setAttribute(
                "form", formValues(request, "kind", "id", "code", "name", "address", "version"));
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
