package vn.codegym.salesinventory.controller;

import jakarta.servlet.http.*;
import java.math.BigDecimal;
import java.util.*;
import vn.codegym.salesinventory.service.*;
import vn.codegym.salesinventory.validation.ProductValidationException;

/**
 * S2-05: Hiển thị danh sách, bộ lọc và xử lý biểu mẫu sản phẩm.
 */
public final class ProductServlet extends PortalServlet {

    protected ProductService products() {
        return new ProductService(source());
    }

    protected List<Map<String, Object>> categories() {
        return new CategoryService(source()).tree();
    }

    private static String filter(HttpServletRequest request, String key, boolean post) {
        return value(
                request,
                post && Set.of("category", "status").contains(key)
                        ? "filter" + Character.toUpperCase(key.charAt(0)) + key.substring(1)
                        : key);
    }

    // Tạo địa chỉ quay về danh sách với bộ lọc hiện tại.
    private static String returnPath(HttpServletRequest request, boolean post) {
        var page =
                vn.codegym.salesinventory.dto.PageRequest.parse(
                        value(request, "page"), value(request, "pageSize"));
        var params = new ArrayList<String>();
        for (String key : List.of("q", "category", "status")) {
            String value = filter(request, key, post);
            if (!value.isEmpty()) {
                params.add(
                        key
                                + "="
                                + java.net.URLEncoder.encode(
                                        value, java.nio.charset.StandardCharsets.UTF_8));
            }
        }
        if (!value(request, "page").isEmpty() || !value(request, "pageSize").isEmpty()) {
            params.add("page=" + page.page());
            params.add("pageSize=" + page.pageSize());
        }
        return "/catalog/products" + (params.isEmpty() ? "" : "?" + String.join("&", params));
    }

    // Lấy danh sách sản phẩm theo điều kiện đầu vào.
    private void list(HttpServletRequest request, boolean post) {
        var filters = new LinkedHashMap<String, String>();
        for (String key : List.of("q", "category", "status")) {
            filters.put(key, filter(request, key, post));
        }
        Long category = null;
        try {
            if (!filters.get("category").isEmpty()) {
                category = Long.valueOf(filters.get("category"));
                if (category <= 0) {
                    category = 0L;
                }
            }
        } catch (NumberFormatException invalid) {
            category = 0L;
        }
        var result =
                products()
                        .search(
                                actor(request).id(),
                                filters.get("q"),
                                category,
                                filters.get("status"),
                                vn.codegym.salesinventory.dto.PageRequest.parse(
                                        value(request, "page"), value(request, "pageSize")));
        request.setAttribute("products", result.items());
        request.setAttribute("pagination", result);
        request.setAttribute("productFilter", filters);
        request.setAttribute("paginationFilterValues", filters);
        request.setAttribute("productReturn", returnPath(request, post));
        request.setAttribute("pageNo", result.page());
        request.setAttribute("totalProducts", result.totalItems());
        request.setAttribute("hasNext", result.page() < result.getTotalPages());
    }

    // Nạp dữ liệu và hiển thị trang sản phẩm.
    protected void get(HttpServletRequest request, HttpServletResponse response) throws Exception {
        request.setAttribute("categories", categories());
        list(request, false);
        if (!value(request, "id").isEmpty()) {
            request.setAttribute(
                    "edit", products().find(actor(request).id(), number(request, "id")));
        }
        Object success = request.getSession().getAttribute("productSuccess");
        request.getSession().removeAttribute("productSuccess");
        request.setAttribute("successMessage", success);
        view(request, response, "catalog/products");
    }

    // Tiếp nhận và xử lý thay đổi sản phẩm.
    protected void post(HttpServletRequest request, HttpServletResponse response) throws Exception {
        access(request).require("PRODUCT_MANAGE");
        if (request.getParameter("cost") != null) {
            access(request).require("COST_WRITE");
        }
        Map<String, Object> form = form(request);
        Map<String, String> errors = new LinkedHashMap<>();
        long id = identifier(request, "id", true, errors);
        if ("delete".equals(value(request, "action"))) {
            if (id <= 0 || !errors.isEmpty()) {
                throw new IllegalArgumentException("Mã sản phẩm không hợp lệ.");
            }
            products().delete(actor(request).id(), id);
            request.getSession().setAttribute("productSuccess", "Đã xóa sản phẩm.");
            redirect(request, response, returnPath(request, true));
            return;
        }
        long category = identifier(request, "category", false, errors);
        long version = identifier(request, "version", id == 0, errors);
        if (id > 0 && version <= 0) {
            errors.put("form", "Phiên bản sản phẩm không hợp lệ. Hãy tải lại.");
        }
        BigDecimal cost = null;
        if (!value(request, "cost").isEmpty()) {
            try {
                if (value(request, "cost").length() > 64) {
                    throw new IllegalArgumentException("Giá vốn vượt giới hạn số thập phân.");
                }
                cost = new BigDecimal(value(request, "cost"));
            } catch (NumberFormatException exception) {
                errors.put("cost", "Giá vốn phải là số không âm, tối đa 4 chữ số thập phân.");
            } catch (IllegalArgumentException e) {
                errors.put("cost", e.getMessage());
            }
        }
        var input =
                new ProductService.Input(
                        value(request, "sku"),
                        value(request, "name"),
                        category,
                        value(request, "baseUnit"),
                        value(request, "packaging"),
                        cost,
                        null,
                        value(request, "status"),
                        version);
        errors.putAll(ProductService.errors(input));
        try {
            if (!errors.isEmpty()) {
                throw new ProductValidationException(errors);
            }
            Part file =
                    request.getContentType() != null
                                    && request.getContentType()
                                            .toLowerCase(Locale.ROOT)
                                            .startsWith("multipart/form-data")
                            ? request.getPart("image")
                            : null;
            if (file != null && file.getSize() > ImageStorage.MAX_BYTES) {
                throw ProductValidationException.field(
                        "image", "Ảnh JPG/PNG phải có dung lượng tối đa 2MB.");
            }
            if (file != null && file.getSize() > 0) {
                try (var stream = file.getInputStream()) {
                    products()
                            .saveWithImage(
                                    actor(request).id(),
                                    id,
                                    input,
                                    stream.readNBytes(ImageStorage.MAX_BYTES + 1));
                }
            } else {
                products().save(actor(request).id(), id, input);
            }
        } catch (ProductValidationException e) {
            response.setStatus(400);
            request.setAttribute("edit", form);
            request.setAttribute("editing", id > 0);
            request.setAttribute("errors", e.errors());
            request.setAttribute("formError", e.errors().get("form"));
            request.setAttribute("categories", categories());
            list(request, true);
            view(request, response, "catalog/products");
            return;
        }
        request.getSession().setAttribute("productSuccess", "Đã lưu sản phẩm.");
        redirect(request, response, returnPath(request, true));
    }

    // Đọc mã tham chiếu của sản phẩm và ghi lỗi vào đúng trường.
    private static long identifier(
            HttpServletRequest request,
            String name,
            boolean allowEmpty,
            Map<String, String> errors) {
        String raw = value(request, name);
        if (allowEmpty && raw.isEmpty()) {
            return 0;
        }
        try {
            long number = Long.parseLong(raw);
            if (number < 0) {
                throw new NumberFormatException();
            }
            return number;
        } catch (NumberFormatException exception) {
            errors.put(
                    name.equals("category") ? "category" : "form",
                    "Mã hoặc phiên bản không hợp lệ.");
            return 0;
        }
    }

    // Đọc dữ liệu biểu mẫu sản phẩm và gom lỗi theo từng trường.
    private Map<String, Object> form(HttpServletRequest request) {
        Map<String, Object> form = new LinkedHashMap<>();
        for (String name : List.of("id", "version", "sku", "name", "packaging", "status")) {
            form.put(name, raw(request, name));
        }
        form.put("category_id", raw(request, "category"));
        form.put("base_unit", raw(request, "baseUnit"));
        if (access(request).allows("COST_READ")) {
            form.put("cost_price", raw(request, "cost"));
        }
        return form;
    }

    private static String raw(HttpServletRequest request, String name) {
        return Objects.requireNonNullElse(request.getParameter(name), "");
    }
}
