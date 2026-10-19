package vn.codegym.salesinventory.controller;

import jakarta.servlet.http.*;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import vn.codegym.salesinventory.dto.PageRequest;
import vn.codegym.salesinventory.service.DealerService;

/**
 * S3-03, S3-08: Hiển thị danh sách, bộ lọc và biểu mẫu hồ sơ đại lý.
 */
public final class DealerServlet extends PortalServlet {

    private DealerService service() {
        return new DealerService(source());
    }

    private static String filterValue(HttpServletRequest request, String key) {
        return value(
                request,
                "POST".equals(request.getMethod())
                        ? "filter" + Character.toUpperCase(key.charAt(0)) + key.substring(1)
                        : key);
    }

    // Đọc mã lọc đại lý và báo lỗi nếu giá trị không hợp lệ.
    private static Long filterId(HttpServletRequest request, String key) {
        String value = filterValue(request, key);
        if (value.isEmpty()) {
            return null;
        }
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Bộ lọc không hợp lệ.");
        }
    }

    // Lấy danh sách hồ sơ đại lý theo điều kiện đầu vào.
    private void list(HttpServletRequest request) {
        var result =
                service()
                        .search(
                                actor(request).id(),
                                new DealerService.Filter(
                                        value(request, "q"),
                                        filterId(request, "territory"),
                                        filterId(request, "group"),
                                        filterId(request, "staff"),
                                        filterValue(request, "status")),
                                PageRequest.parse(
                                        value(request, "page"), value(request, "pageSize")));
        request.setAttribute("dealers", result.items());
        request.setAttribute("pagination", result);
        var selected = new java.util.LinkedHashMap<String, String>();
        selected.put("q", value(request, "q"));
        for (String key : new String[] {"group", "territory", "staff", "status"}) {
            selected.put(key, filterValue(request, key));
        }
        request.setAttribute("dealerFilter", selected);
        request.setAttribute("paginationFilterValues", selected);
        service().filterOptions(actor(request).id()).forEach(request::setAttribute);
        if (access(request).allows("DEALER_MANAGE")) {
            service().options(actor(request).id()).forEach(request::setAttribute);
        }
    }

    // Nạp dữ liệu và hiển thị trang hồ sơ đại lý.
    protected void get(HttpServletRequest request, HttpServletResponse response) throws Exception {
        list(request);
        if (!value(request, "id").isEmpty()) {
            request.setAttribute(
                    "edit", service().find(actor(request).id(), number(request, "id")));
        }
        view(request, response, "dealers/list");
    }

    // Tiếp nhận và xử lý thay đổi hồ sơ đại lý.
    protected void post(HttpServletRequest request, HttpServletResponse response) throws Exception {
        access(request).require("DEALER_MANAGE");
        var form =
                formValues(
                        request,
                        "id",
                        "version",
                        "code",
                        "name",
                        "taxCode",
                        "phone",
                        "group",
                        "territory",
                        "staff",
                        "warehouse",
                        "status");
        for (var pair :
                new String[][] {
                    {"taxCode", "tax_code"},
                    {"group", "group_id"},
                    {"territory", "territory_id"},
                    {"staff", "primary_staff_id"},
                    {"warehouse", "warehouse_id"}
                }) {
            form.put(pair[1], form.remove(pair[0]));
        }
        try {
            long id = value(request, "id").isEmpty() ? 0 : number(request, "id");
            long version = value(request, "version").isEmpty() ? 0 : number(request, "version");
            if ("delete".equals(value(request, "action"))) {
                service().delete(actor(request).id(), id, version);
            } else {
                service()
                        .save(
                                actor(request).id(),
                                id,
                                new DealerService.Input(
                                        value(request, "code"),
                                        value(request, "name"),
                                        value(request, "taxCode"),
                                        value(request, "phone"),
                                        number(request, "group"),
                                        number(request, "territory"),
                                        number(request, "staff"),
                                        value(request, "warehouse").isEmpty()
                                                ? null
                                                : number(request, "warehouse"),
                                        value(request, "status"),
                                        version));
            }
            String filters = "";
            for (String key : new String[] {"group", "territory", "staff", "status"}) {
                filters +=
                        "&"
                                + key
                                + "="
                                + URLEncoder.encode(
                                        filterValue(request, key), StandardCharsets.UTF_8);
            }
            redirect(
                    request,
                    response,
                    "/dealers?notice=saved&q="
                            + URLEncoder.encode(value(request, "q"), StandardCharsets.UTF_8)
                            + "&page="
                            + PageRequest.parse(value(request, "page"), value(request, "pageSize"))
                                    .page()
                            + "&pageSize="
                            + PageRequest.parse(value(request, "page"), value(request, "pageSize"))
                                    .pageSize()
                            + filters);
        } catch (IllegalArgumentException invalid) {
            response.setStatus(400);
            request.setAttribute("edit", form);
            request.setAttribute("errors", fieldErrors(invalid));
            list(request);
            view(request, response, "dealers/list");
        }
    }
}
