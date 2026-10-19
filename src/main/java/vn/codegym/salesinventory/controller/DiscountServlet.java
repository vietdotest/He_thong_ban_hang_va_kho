package vn.codegym.salesinventory.controller;

import jakarta.servlet.http.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import vn.codegym.salesinventory.dto.PageRequest;
import vn.codegym.salesinventory.service.*;
import vn.codegym.salesinventory.validation.FieldValidationException;

/**
 * S3-01: Hiển thị và xử lý cấu hình chính sách chiết khấu.
 */
public final class DiscountServlet extends PortalServlet {

    private final DiscountService configured;

    private final PricingService pricing;

    private final CategoryService categories;

    private final ProductService products;

    public DiscountServlet() {
        this(null, null, null, null);
    }

    DiscountServlet(
            DiscountService service,
            PricingService pricing,
            CategoryService categories,
            ProductService products) {
        this.configured = service;
        this.pricing = pricing;
        this.categories = categories;
        this.products = products;
    }

    private DiscountService service() {
        return configured == null ? new DiscountService(source()) : configured;
    }

    // Nạp dữ liệu chính sách chiết khấu cho màn hình.
    private void load(HttpServletRequest request) {
        var result =
                service()
                        .search(
                                actor(request).id(),
                                value(request, "q"),
                                PageRequest.parse(
                                        value(request, "page"), value(request, "pageSize")));
        request.setAttribute("policies", result.items());
        request.setAttribute("pagination", result);
        request.setAttribute(
                "groups", (pricing == null ? new PricingService(source()) : pricing).groups());
        request.setAttribute(
                "categories",
                (categories == null ? new CategoryService(source()) : categories).tree());
    }

    // Nạp dữ liệu và hiển thị trang chính sách chiết khấu.
    protected void get(HttpServletRequest request, HttpServletResponse response) throws Exception {
        load(request);
        if (!value(request, "id").isEmpty()) {
            var edit = service().find(actor(request).id(), number(request, "id"));
            edit.put("targetType", edit.get("product_id") == null ? "CATEGORY" : "SKU");
            request.setAttribute("edit", edit);
            if (edit.get("product_id") != null) {
                request.setAttribute(
                        "selectedProduct",
                        (products == null ? new ProductService(source()) : products)
                                .find(
                                        actor(request).id(),
                                        ((Number) edit.get("product_id")).longValue()));
            }
        }
        view(request, response, "pricing/discounts");
    }

    // Tiếp nhận và xử lý thay đổi chính sách chiết khấu.
    protected void post(HttpServletRequest request, HttpServletResponse response) throws Exception {
        access(request).require("DISCOUNT_MANAGE");
        var form =
                formValues(
                        request,
                        "id",
                        "revision",
                        "name",
                        "targetType",
                        "product",
                        "category",
                        "group",
                        "mode",
                        "from",
                        "to",
                        "status");
        form.put("product_id", form.get("product"));
        form.put("category_id", form.get("category"));
        form.put("customer_group_id", form.get("group"));
        form.put("valid_from", form.get("from"));
        form.put("valid_to", form.get("to"));
        String[] minimum = request.getParameterValues("minimum"),
                value = request.getParameterValues("discountValue");
        var shown = new ArrayList<Map<String, String>>();
        if (minimum != null && value != null) {
            for (int i = 0; i < Math.min(100, Math.min(minimum.length, value.length)); i++) {
                shown.add(Map.of("minimum_quantity", minimum[i], "discount_value", value[i]));
            }
        }
        form.put("tiers", shown);
        try {
            if (minimum == null
                    || value == null
                    || minimum.length != value.length
                    || minimum.length < 1
                    || minimum.length > 100) {
                throw FieldValidationException.field("tiers", "Chính sách cần 1–100 bậc hợp lệ.");
            }
            var tiers = new ArrayList<DiscountService.TierInput>();
            var errors = new LinkedHashMap<String, String>();
            for (int i = 0; i < minimum.length; i++) {
                BigDecimal q = null, d = null;
                try {
                    q = new BigDecimal(minimum[i]);
                } catch (NumberFormatException exception) {
                    errors.put("minimum" + i, "Mốc sản lượng phải là số không âm.");
                }
                try {
                    d = new BigDecimal(value[i]);
                } catch (NumberFormatException e) {
                    errors.put("value" + i, "Giá trị chiết khấu không hợp lệ.");
                }
                tiers.add(new DiscountService.TierInput(q, d));
            }
            if (!errors.isEmpty()) {
                throw new FieldValidationException(errors);
            }
            if (!Set.of("SKU", "CATEGORY").contains(value(request, "targetType"))) {
                throw FieldValidationException.field("target", "Loại đối tượng không hợp lệ.");
            }
            Long
                    product =
                            "SKU".equals(value(request, "targetType"))
                                    ? number(request, "product")
                                    : null,
                    category =
                            "CATEGORY".equals(value(request, "targetType"))
                                    ? number(request, "category")
                                    : null;
            LocalDate from, to;
            try {
                from = LocalDate.parse(value(request, "from"));
                to = LocalDate.parse(value(request, "to"));
            } catch (java.time.DateTimeException e) {
                throw FieldValidationException.field("from", "Ngày áp dụng không hợp lệ.");
            }
            service()
                    .save(
                            actor(request).id(),
                            value(request, "id").isEmpty() ? 0 : number(request, "id"),
                            new DiscountService.Input(
                                    value(request, "name"),
                                    product,
                                    category,
                                    value(request, "group").isEmpty()
                                            ? null
                                            : number(request, "group"),
                                    value(request, "mode"),
                                    from,
                                    to,
                                    value(request, "status"),
                                    value(request, "revision").isEmpty()
                                            ? 0
                                            : number(request, "revision"),
                                    tiers));
            redirect(request, response, "/pricing/discounts?notice=saved");
        } catch (IllegalArgumentException e) {
            response.setStatus(400);
            request.setAttribute("edit", form);
            request.setAttribute("errors", fieldErrors(e));
            load(request);
            view(request, response, "pricing/discounts");
        }
    }
}
