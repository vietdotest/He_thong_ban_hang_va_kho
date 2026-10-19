package vn.codegym.salesinventory.controller;

import jakarta.servlet.http.*;
import vn.codegym.salesinventory.service.*;
import vn.codegym.salesinventory.validation.*;
import vn.codegym.salesinventory.dto.PageRequest;
import vn.codegym.salesinventory.dao.Sql;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * S2-07: Hiển thị và xử lý đơn vị tính cùng hệ số quy đổi.
 */
public final class UnitServlet extends PortalServlet {

    private final UnitService configured;

    private final ProductService products;

    public UnitServlet() {
        configured = null;
        products = null;
    }

    UnitServlet(UnitService configured, ProductService products) {
        this.configured = configured;
        this.products = products;
    }

    private UnitService service() {
        return configured == null ? new UnitService(source()) : configured;
    }

    private ProductService products() {
        return products == null ? new ProductService(source()) : products;
    }

    // Hiển thị trang đơn vị tính và hệ số quy đổi cùng dữ liệu đã chuẩn bị.
    private void render(HttpServletRequest request, HttpServletResponse response, long product)
            throws Exception {
        var options =
                new ArrayList<Map<String, Object>>(
                        products()
                                .list(
                                        actor(request).id(),
                                        value(request, "productQuery"),
                                        null,
                                        "",
                                        1));
        if (product > 0 && options.stream().noneMatch(p -> Sql.id(p.get("id")) == product)) {
            options.add(products().find(actor(request).id(), product));
        }
        request.setAttribute("products", options);
        request.setAttribute("productId", product);
        if (product > 0) {
            var page =
                    service()
                            .search(
                                    actor(request).id(),
                                    product,
                                    value(request, "q"),
                                    PageRequest.parse(
                                            value(request, "page"), value(request, "pageSize")));
            request.setAttribute("units", page.items());
            request.setAttribute("pagination", page);
        }
        request.setAttribute(
                "paginationExtraParameters",
                Map.of(
                        "product",
                        String.valueOf(product),
                        "productQuery",
                        value(request, "productQuery")));
        request.setAttribute("unitReturn", returnPath(request, product));
        request.setAttribute("warehouses", access(request).warehouses());
        view(request, response, "catalog/units");
    }

    private static String returnPath(HttpServletRequest request, long product) {
        var page = PageRequest.parse(value(request, "page"), value(request, "pageSize"));
        return "/catalog/units?product="
                + product
                + "&q="
                + URLEncoder.encode(value(request, "q"), StandardCharsets.UTF_8)
                + "&productQuery="
                + URLEncoder.encode(value(request, "productQuery"), StandardCharsets.UTF_8)
                + "&page="
                + page.page()
                + "&pageSize="
                + page.pageSize();
    }

    // Nạp dữ liệu và hiển thị trang đơn vị tính và hệ số quy đổi.
    protected void get(HttpServletRequest request, HttpServletResponse response) throws Exception {
        access(request).require("CATALOG_READ");
        long product = 0;
        try {
            product = value(request, "product").isEmpty() ? 0 : number(request, "product");
            if (!value(request, "edit").isEmpty()) {
                var edit = service().find(actor(request).id(), number(request, "edit"));
                if (vn.codegym.salesinventory.dao.Sql.id(edit.get("product_id")) != product) {
                    throw new IllegalArgumentException("Đơn vị không thuộc sản phẩm đã chọn.");
                }
                request.setAttribute("edit", edit);
            }
            if (value(request, "action").equals("convert")) {
                java.math.BigDecimal quantity;
                try {
                    quantity = CatalogValidation.decimal(value(request, "quantity"), 6, false);
                } catch (IllegalArgumentException invalid) {
                    throw FieldValidationException.field("quantity", invalid.getMessage());
                }
                var converted =
                        service().convert(actor(request).id(), number(request, "id"), quantity);
                if (converted.productId() != product) {
                    throw new IllegalArgumentException("Đơn vị không thuộc sản phẩm đã chọn.");
                }
                request.setAttribute(
                        "conversion",
                        converted.quantity()
                                + " "
                                + converted.unitName()
                                + " = "
                                + converted.baseQuantity()
                                + " đơn vị cơ sở (hệ số "
                                + converted.factor()
                                + ", phiên bản "
                                + converted.version()
                                + ")");
            }
        } catch (IllegalArgumentException invalid) {
            response.setStatus(400);
            request.setAttribute("errors", fieldErrors(invalid));
        }
        render(request, response, product);
    }

    // Tiếp nhận và xử lý thay đổi đơn vị tính và hệ số quy đổi.
    protected void post(HttpServletRequest request, HttpServletResponse response) throws Exception {
        access(request).require("WAREHOUSE_MANAGE");
        long product = 0;
        var form = formValues(request, "id", "product", "name", "factor", "warehouse", "version");
        form.put("warehouse_id", form.remove("warehouse"));
        try {
            product = number(request, "product");
            long id = value(request, "id").isEmpty() ? 0 : number(request, "id");
            if (value(request, "action").equals("delete")) {
                service().delete(actor(request).id(), id, product);
            } else {
                java.math.BigDecimal factor;
                try {
                    factor = CatalogValidation.decimal(value(request, "factor"), 6, true);
                } catch (IllegalArgumentException invalid) {
                    throw FieldValidationException.field("factor", invalid.getMessage());
                }
                service()
                        .save(
                                actor(request).id(),
                                id,
                                product,
                                value(request, "name"),
                                factor,
                                number(request, "warehouse"),
                                value(request, "version").isEmpty()
                                        ? 0
                                        : number(request, "version"));
            }
            redirect(request, response, returnPath(request, product) + "&notice=saved");
        } catch (IllegalArgumentException invalid) {
            response.setStatus(400);
            request.setAttribute("edit", form);
            request.setAttribute("errors", fieldErrors(invalid));
            render(request, response, product);
        }
    }
}
