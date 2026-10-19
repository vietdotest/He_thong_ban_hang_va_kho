package vn.codegym.salesinventory.controller;

import jakarta.servlet.http.*;
import vn.codegym.salesinventory.service.CategoryService;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * S2-06: Hiển thị và xử lý biểu mẫu quản lý nhóm hàng.
 */
public final class CategoryServlet extends PortalServlet {

    private final CategoryService configured;

    public CategoryServlet() {
        configured = null;
    }

    CategoryServlet(CategoryService configured) {
        this.configured = configured;
    }

    private CategoryService service() {
        return configured == null ? new CategoryService(source()) : configured;
    }

    // Lấy các lựa chọn cần dùng trên biểu mẫu nhóm hàng.
    private void options(HttpServletRequest request) {
        var tree = service().tree(actor(request).id(), value(request, "q"));
        request.setAttribute("categories", tree);
        request.setAttribute("categoryCount", tree.size());
        request.setAttribute("categoryReturn", returnPath(request));
    }

    private static String returnPath(HttpServletRequest request) {
        return "/catalog/categories?q="
                + URLEncoder.encode(value(request, "q"), StandardCharsets.UTF_8);
    }

    // Nạp dữ liệu và hiển thị trang nhóm hàng.
    protected void get(HttpServletRequest request, HttpServletResponse response) throws Exception {
        access(request).require("CATALOG_READ");
        options(request);
        if (!value(request, "id").isEmpty()) {
            request.setAttribute(
                    "edit", service().find(actor(request).id(), number(request, "id")));
        }
        view(request, response, "catalog/categories");
    }

    // Tiếp nhận và xử lý thay đổi nhóm hàng.
    protected void post(HttpServletRequest request, HttpServletResponse response) throws Exception {
        access(request).require("PRODUCT_MANAGE");
        var form = formValues(request, "id", "version", "code", "name", "parent");
        form.put("parent_id", form.remove("parent"));
        try {
            long id = value(request, "id").isEmpty() ? 0 : number(request, "id");
            if (value(request, "action").equals("delete")) {
                service().delete(actor(request).id(), id);
            } else {
                service()
                        .save(
                                actor(request).id(),
                                id,
                                value(request, "code"),
                                value(request, "name"),
                                value(request, "parent").isEmpty()
                                        ? null
                                        : number(request, "parent"),
                                value(request, "version").isEmpty()
                                        ? 0
                                        : number(request, "version"));
            }
            redirect(request, response, returnPath(request) + "&notice=saved");
        } catch (IllegalArgumentException invalid) {
            response.setStatus(400);
            request.setAttribute("edit", form);
            request.setAttribute("errors", fieldErrors(invalid));
            options(request);
            view(request, response, "catalog/categories");
        }
    }
}
