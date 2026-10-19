package vn.codegym.salesinventory.controller;

import jakarta.servlet.http.*;
import java.time.*;
import java.math.BigDecimal;
import java.util.*;
import vn.codegym.salesinventory.service.*;
import vn.codegym.salesinventory.dao.Sql;
import vn.codegym.salesinventory.validation.*;
import vn.codegym.salesinventory.dto.PageRequest;

/**
 * S2-10: Hiển thị và lưu bảng giá theo nhóm khách hàng, thời gian hiệu lực.
 */
public final class PricingServlet extends PortalServlet {

    private final PricingService configured;

    private final ProductService configuredProducts;

    public PricingServlet() {
        configured = null;
        configuredProducts = null;
    }

    PricingServlet(PricingService service, ProductService products) {
        configured = service;
        configuredProducts = products;
    }

    private PricingService service() {
        return configured == null ? new PricingService(source()) : configured;
    }

    private ProductService products() {
        return configuredProducts == null ? new ProductService(source()) : configuredProducts;
    }

    // Đọc ngày hiệu lực từ biểu mẫu bảng giá.
    private LocalDate date(HttpServletRequest request, String key) {
        try {
            return LocalDate.parse(value(request, key));
        } catch (DateTimeException invalid) {
            throw FieldValidationException.field(key, "Ngày hiệu lực không hợp lệ.");
        }
    }

    // Đọc giá trị tiền và báo lỗi nếu định dạng số không hợp lệ.
    private BigDecimal decimal(HttpServletRequest request, String key) {
        try {
            return CatalogValidation.decimal(value(request, key), 4, false);
        } catch (IllegalArgumentException invalid) {
            throw FieldValidationException.field(key, invalid.getMessage());
        }
    }

    // Đọc mã tham chiếu và gắn lỗi với trường tương ứng trên biểu mẫu.
    private long fieldId(HttpServletRequest request, String key) {
        try {
            return number(request, key);
        } catch (IllegalArgumentException invalid) {
            throw FieldValidationException.field(key, "Mã bản ghi không hợp lệ.");
        }
    }

    // Giữ bộ lọc và phân trang khi chuyển giữa danh sách và chi tiết bảng giá.
    private static Map<String, String> listContext(HttpServletRequest request) {
        var params = new LinkedHashMap<String, String>();
        if (!value(request, "groupFilter").isEmpty()) {
            params.put("groupFilter", value(request, "groupFilter"));
        }
        for (String key : List.of("q", "versionQuery", "itemQuery")) {
            params.put(key, value(request, key));
        }
        for (String prefix : List.of("version", "item")) {
            var page =
                    PageRequest.parse(
                            value(request, prefix + "Page"), value(request, prefix + "PageSize"));
            params.put(prefix + "Page", Integer.toString(page.page()));
            params.put(prefix + "PageSize", Integer.toString(page.pageSize()));
        }
        return params;
    }

    private static String returnPath(HttpServletRequest request, long id) {
        return "/pricing/lists?"
                + (id > 0 ? "id=" + id + "&" : "")
                + listContext(request).entrySet().stream()
                        .map(
                                p ->
                                        p.getKey()
                                                + "="
                                                + java.net.URLEncoder.encode(
                                                        p.getValue(),
                                                        java.nio.charset.StandardCharsets.UTF_8))
                        .collect(java.util.stream.Collectors.joining("&"));
    }

    // Nạp dữ liệu lựa chọn dùng chung cho biểu mẫu bảng giá.
    private void baseOptions(HttpServletRequest request) {
        var service = service();
        request.setAttribute("groups", service.groups());
        Long selectedGroup =
                value(request, "groupFilter").isEmpty() ? null : fieldId(request, "groupFilter");
        request.setAttribute("selectedPriceGroup", selectedGroup);
        var page =
                PageRequest.parse(value(request, "versionPage"), value(request, "versionPageSize"));
        var versions =
                selectedGroup == null
                        ? service.searchVersions(
                                actor(request).id(), value(request, "versionQuery"), page)
                        : service.searchVersions(
                                actor(request).id(),
                                value(request, "versionQuery"),
                                page,
                                selectedGroup);
        request.setAttribute("versions", versions.items());
        request.setAttribute("versionPagination", versions);
        var parentOptions = new ArrayList<>(versions.items());
        if (value(request, "parent").matches("[1-9][0-9]{0,17}")) {
            long parent = Long.parseLong(value(request, "parent"));
            if (parentOptions.stream().noneMatch(value -> Sql.id(value.get("id")) == parent)) {
                try {
                    var selected = service.findVersion(actor(request).id(), parent);
                    if (selected != null) {
                        parentOptions.add(selected);
                    }
                } catch (IllegalArgumentException missing) {
                    parentOptions.add(
                            Map.of(
                                    "id",
                                    parent,
                                    "group_name",
                                    "Không còn trong danh mục",
                                    "name",
                                    "Phiên bản #" + parent));
                }
            }
        }
        request.setAttribute("inheritanceVersions", parentOptions);
        var productOptions =
                new ArrayList<>(
                        products().list(actor(request).id(), value(request, "q"), null, "", 1));
        if (value(request, "product").matches("[1-9][0-9]{0,17}")) {
            long product = Long.parseLong(value(request, "product"));
            if (productOptions.stream().noneMatch(p -> Sql.id(p.get("id")) == product)) {
                try {
                    var selected = products().find(actor(request).id(), product);
                    if (selected != null) {
                        productOptions.add(selected);
                    }
                } catch (IllegalArgumentException missing) {
                    productOptions.add(
                            Map.of(
                                    "id",
                                    product,
                                    "sku",
                                    Long.toString(product),
                                    "name",
                                    "Không còn trong danh mục"));
                }
            }
        }
        request.setAttribute("products", productOptions);
        request.setAttribute("priceContext", listContext(request));
        var pager = new LinkedHashMap<>(listContext(request));
        pager.remove("q");
        request.setAttribute("pricePagerContext", pager);
        request.setAttribute("priceReturn", returnPath(request, 0));
        request.setAttribute(
                "today", LocalDate.now(vn.codegym.salesinventory.config.VietnamTime.ZONE));
    }

    // Lấy các lựa chọn cần dùng trên biểu mẫu bảng giá.
    private void options(HttpServletRequest request) {
        baseOptions(request);
        Map<String, Object> edit = null;
        if (!value(request, "id").isEmpty()) {
            edit = service().findVersion(actor(request).id(), fieldId(request, "id"));
            if (edit == null) {
                throw FieldValidationException.field("form", "Không tìm thấy phiên bản.");
            }
        } else if (!value(request, "new").equals("1")) {
            edit =
                    service().versions(actor(request).id()).stream()
                            .filter(
                                    value ->
                                            "ACTIVE".equals(value.get("effective_state"))
                                                    && (request.getAttribute("selectedPriceGroup")
                                                                    == null
                                                            || Sql.id(value.get("group_id"))
                                                                    == ((Number)
                                                                                    request
                                                                                            .getAttribute(
                                                                                                    "selectedPriceGroup"))
                                                                            .longValue()))
                            .sorted(Comparator.comparingLong(v -> Sql.id(v.get("group_id"))))
                            .findFirst()
                            .orElse(null);
        }
        long id = edit == null ? 0 : Sql.id(edit.get("id"));
        request.setAttribute("priceReturn", returnPath(request, id));
        var pager = new LinkedHashMap<>(listContext(request));
        pager.remove("q");
        if (id > 0) {
            pager.put("id", Long.toString(id));
        }
        request.setAttribute("pricePagerContext", pager);
        if (edit != null) {
            request.setAttribute("edit", edit);
            var items =
                    service()
                            .searchItems(
                                    actor(request).id(),
                                    id,
                                    value(request, "itemQuery"),
                                    PageRequest.parse(
                                            value(request, "itemPage"),
                                            value(request, "itemPageSize")));
            request.setAttribute("items", items.items());
            request.setAttribute("itemPagination", items);
        }
    }

    // Nạp dữ liệu và hiển thị trang bảng giá.
    protected void get(HttpServletRequest request, HttpServletResponse response) throws Exception {
        access(request).require("PRICE_READ");
        try {
            options(request);
            if (value(request, "action").equals("quote")) {
                var quote =
                        service()
                                .quote(
                                        actor(request).id(),
                                        fieldId(request, "group"),
                                        fieldId(request, "product"),
                                        date(request, "date"));
                request.setAttribute(
                        "quote",
                        "Giá bán: "
                                + quote.sellingPrice()
                                + "; giá sàn: "
                                + quote.floorPrice()
                                + "; phiên bản: "
                                + quote.versionId());
            }
        } catch (IllegalArgumentException invalid) {
            response.setStatus(400);
            request.setAttribute("errors", fieldErrors(invalid));
        }
        view(request, response, "pricing/prices");
    }

    // Tiếp nhận và xử lý thay đổi bảng giá.
    protected void post(HttpServletRequest request, HttpServletResponse response) throws Exception {
        access(request).require("PRICE_MANAGE");
        var form =
                formValues(
                        request,
                        "action",
                        "id",
                        "revision",
                        "name",
                        "group",
                        "from",
                        "to",
                        "parent",
                        "product",
                        "selling",
                        "floor",
                        "item");
        request.setAttribute("form", form);
        try {
            long id = value(request, "id").isEmpty() ? 0 : fieldId(request, "id");
            var service = service();
            switch (value(request, "action")) {
                case "create" ->
                        id =
                                service.create(
                                        actor(request).id(),
                                        fieldId(request, "group"),
                                        value(request, "name"),
                                        date(request, "from"),
                                        date(request, "to"),
                                        value(request, "parent").isEmpty()
                                                ? null
                                                : fieldId(request, "parent"));
                case "edit" ->
                        service.edit(
                                actor(request).id(),
                                id,
                                fieldId(request, "revision"),
                                value(request, "name"),
                                date(request, "from"),
                                date(request, "to"));
                case "item" ->
                        service.saveItem(
                                actor(request).id(),
                                id,
                                fieldId(request, "product"),
                                decimal(request, "selling"),
                                decimal(request, "floor"),
                                fieldId(request, "revision"));
                case "deleteItem" ->
                        service.deleteItem(
                                actor(request).id(),
                                id,
                                fieldId(request, "item"),
                                fieldId(request, "revision"));
                default -> throw FieldValidationException.field("form", "Thao tác không hợp lệ.");
            }
            redirect(request, response, returnPath(request, id) + "&notice=saved");
        } catch (IllegalArgumentException invalid) {
            response.setStatus(400);
            request.setAttribute("errors", fieldErrors(invalid));
            try {
                options(request);
            } catch (IllegalArgumentException missing) {
                baseOptions(request);
            }
            view(request, response, "pricing/prices");
        }
    }
}
