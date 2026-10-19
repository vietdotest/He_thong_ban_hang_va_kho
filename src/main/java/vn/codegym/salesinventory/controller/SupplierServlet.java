package vn.codegym.salesinventory.controller;

import jakarta.servlet.http.*;
import vn.codegym.salesinventory.service.SupplierService;
import vn.codegym.salesinventory.dto.PageRequest;
import vn.codegym.salesinventory.validation.FieldValidationException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * S2-09: Hiển thị và xử lý biểu mẫu nhà cung cấp.
 */
public final class SupplierServlet extends PortalServlet {

    private final SupplierService configured;

    public SupplierServlet() {
        configured = null;
    }

    SupplierServlet(SupplierService configured) {
        this.configured = configured;
    }

    private SupplierService service() {
        return configured == null ? new SupplierService(source()) : configured;
    }

    // Lấy các lựa chọn cần dùng trên biểu mẫu nhà cung cấp.
    private void options(HttpServletRequest request) {
        var page =
                service()
                        .search(
                                actor(request).id(),
                                value(request, "q"),
                                PageRequest.parse(
                                        value(request, "page"), value(request, "pageSize")));
        request.setAttribute("suppliers", page.items());
        request.setAttribute("pagination", page);
        request.setAttribute("warehouses", access(request).warehouses());
        request.setAttribute("supplierReturn", returnPath(request));
    }

    private static String returnPath(HttpServletRequest request) {
        var page = PageRequest.parse(value(request, "page"), value(request, "pageSize"));
        return "/catalog/suppliers?q="
                + URLEncoder.encode(value(request, "q"), StandardCharsets.UTF_8)
                + "&page="
                + page.page()
                + "&pageSize="
                + page.pageSize();
    }

    // Đọc kho đang chọn trong biểu mẫu nhà cung cấp.
    private static long warehouseNumber(HttpServletRequest request) {
        try {
            return number(request, "warehouse");
        } catch (IllegalArgumentException invalid) {
            throw FieldValidationException.field("warehouse", "Hãy chọn kho hợp lệ.");
        }
    }

    // Nạp dữ liệu và hiển thị trang nhà cung cấp.
    protected void get(HttpServletRequest request, HttpServletResponse response) throws Exception {
        access(request).require("CATALOG_READ");
        options(request);
        if (!value(request, "id").isEmpty()) {
            request.setAttribute(
                    "edit", service().find(actor(request).id(), number(request, "id")));
        }
        view(request, response, "catalog/suppliers");
    }

    // Tiếp nhận và xử lý thay đổi nhà cung cấp.
    protected void post(HttpServletRequest request, HttpServletResponse response) throws Exception {
        access(request).require("WAREHOUSE_MANAGE");
        var form =
                formValues(
                        request,
                        "id",
                        "version",
                        "code",
                        "name",
                        "taxCode",
                        "contact",
                        "phone",
                        "terms",
                        "warehouse",
                        "status");
        for (var pair :
                new String[][] {
                    {"taxCode", "tax_code"},
                    {"contact", "contact_name"},
                    {"phone", "contact_phone"},
                    {"terms", "payment_terms"},
                    {"warehouse", "warehouse_id"}
                }) {
            form.put(pair[1], form.remove(pair[0]));
        }
        try {
            long id = value(request, "id").isEmpty() ? 0 : number(request, "id");
            if (value(request, "action").equals("delete")) {
                service().delete(actor(request).id(), id);
            } else {
                service()
                        .save(
                                actor(request).id(),
                                id,
                                new SupplierService.Input(
                                        value(request, "code"),
                                        value(request, "name"),
                                        value(request, "taxCode"),
                                        value(request, "contact"),
                                        value(request, "phone"),
                                        value(request, "terms"),
                                        warehouseNumber(request),
                                        value(request, "status"),
                                        value(request, "version").isEmpty()
                                                ? 0
                                                : number(request, "version")));
            }
            redirect(request, response, returnPath(request) + "&notice=saved");
        } catch (IllegalArgumentException invalid) {
            response.setStatus(400);
            request.setAttribute("edit", form);
            request.setAttribute("errors", fieldErrors(invalid));
            options(request);
            view(request, response, "catalog/suppliers");
        }
    }
}
