package vn.codegym.salesinventory.controller;

import jakarta.servlet.http.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import vn.codegym.salesinventory.dao.Sql;
import vn.codegym.salesinventory.dto.PageRequest;
import vn.codegym.salesinventory.service.*;
import vn.codegym.salesinventory.validation.FieldValidationException;

/**
 * S3-09, S4-01/02/03/05/06/07/08/10 (phần đã có): Nhận thao tác trên đơn và chuẩn bị trang danh sách, chi tiết, in.
 */
public class OrderServlet extends PortalServlet {

    private final OrderService configured;

    private final DealerService dealers;

    private final ProductService products;

    public OrderServlet() {
        this(null, null, null);
    }

    OrderServlet(OrderService service, DealerService dealers, ProductService products) {
        this.configured = service;
        this.dealers = dealers;
        this.products = products;
    }

    private OrderService service() {
        return configured == null ? new OrderService(source()) : configured;
    }

    private DealerService dealers() {
        return dealers == null ? new DealerService(source()) : dealers;
    }

    private ProductService products() {
        return products == null ? new ProductService(source()) : products;
    }

    protected boolean portalMode() {
        return false;
    }

    private String path() {
        return portalMode() ? "/portal/orders" : "/orders";
    }

    private String permission(boolean write) {
        return portalMode()
                ? (write ? "PORTAL_ORDER_WRITE" : "PORTAL_ORDER_READ")
                : (write ? "ORDER_WRITE" : "ORDER_READ");
    }

    private void layout(HttpServletRequest request) {
        request.setAttribute("portalMode", portalMode());
        request.setAttribute("orderPath", path());
        request.setAttribute(
                "optionsPath", portalMode() ? "/api/portal/options" : "/api/orders/options");
    }

    // Đọc các bộ lọc danh sách đơn hàng từ yêu cầu.
    private static String filters(HttpServletRequest request) {
        var result = new StringBuilder();
        for (String key :
                List.of(
                        "q",
                        "status",
                        "page",
                        "pageSize",
                        "filterDealer",
                        "staff",
                        "territory",
                        "from",
                        "to")) {
            if (!value(request, key).isEmpty()) {
                result.append('&')
                        .append(key.equals("filterDealer") ? "dealer" : key)
                        .append('=')
                        .append(
                                java.net.URLEncoder.encode(
                                        value(request, key),
                                        java.nio.charset.StandardCharsets.UTF_8));
            }
        }
        return result.toString();
    }

    private static long optional(HttpServletRequest request, String key) {
        return value(request, key).isEmpty() ? 0 : number(request, key);
    }

    // Giữ lại dữ liệu các dòng hàng để hiển thị lại biểu mẫu.
    private static List<Map<String, Object>> lineValues(HttpServletRequest request) {
        var rows = new ArrayList<Map<String, Object>>();
        String[] products = request.getParameterValues("product"),
                units = request.getParameterValues("unit"),
                quantities = request.getParameterValues("quantity");
        if (products == null) {
            return rows;
        }
        if (products.length > 100) {
            throw FieldValidationException.field("lines", "Tối đa 100 dòng hàng.");
        }
        for (int i = 0; i < products.length; i++) {
            rows.add(
                    Map.of(
                            "product_id",
                            products[i],
                            "unit_id",
                            units != null && i < units.length ? units[i] : "",
                            "quantity",
                            quantities != null && i < quantities.length ? quantities[i] : ""));
        }
        return rows;
    }

    // Đọc dữ liệu biểu mẫu thành đầu vào cho đơn hàng.
    private static OrderService.Input input(HttpServletRequest request) {
        var values = lineValues(request);
        String[] units = request.getParameterValues("unit"),
                quantities = request.getParameterValues("quantity");
        if (units == null
                || quantities == null
                || units.length != values.size()
                || quantities.length != values.size()) {
            throw FieldValidationException.field(
                    "lines", "Số trường sản phẩm / đơn vị / số lượng không khớp.");
        }
        var lines = new ArrayList<OrderService.LineInput>();
        var errors = new LinkedHashMap<String, String>();
        for (int i = 0; i < values.size(); i++) {
            var row = values.get(i);
            try {
                if (row.get("quantity").toString().length() > 128) {
                    throw new NumberFormatException();
                }
                lines.add(
                        new OrderService.LineInput(
                                Long.parseLong(row.get("product_id").toString()),
                                Long.parseLong(row.get("unit_id").toString()),
                                new BigDecimal(row.get("quantity").toString())));
            } catch (IllegalArgumentException exception) {
                errors.put("line" + i, "Chọn sản phẩm, đơn vị và số lượng hợp lệ.");
            }
        }
        if (!errors.isEmpty()) {
            throw new FieldValidationException(errors);
        }
        LocalDate delivery;
        try {
            delivery = LocalDate.parse(value(request, "delivery"));
        } catch (Exception e) {
            delivery = null;
        }
        return new OrderService.Input(
                optional(request, "dealer"),
                optional(request, "address"),
                delivery,
                optional(request, "version"),
                value(request, "creationKey"),
                lines);
    }

    private static void json(HttpServletResponse response, Map<String, Object> values)
            throws Exception {
        response.setContentType("application/json;charset=UTF-8");
        response.setHeader("Cache-Control", "no-store");
        response.getWriter().write(AuditService.snapshot(values, false));
    }

    // Kiểm tra quyền sửa trước khi thay đổi dữ liệu.
    private void editor(HttpServletRequest request, long id, boolean quote) throws Exception {
        layout(request);
        long actor = actor(request).id();
        Map<String, Object> order = id == 0 ? Map.of() : service().find(actor, id);
        request.setAttribute("order", order);
        Map<String, Object> portalDealer =
                portalMode() ? new PortalAccountService(source(), null).profile(actor) : Map.of();
        if (portalMode()
                && !value(request, "dealer").isEmpty()
                && number(request, "dealer") != Sql.id(portalDealer.get("id"))) {
            throw new SecurityException();
        }
        if (request.getAttribute("form") == null) {
            var form = new LinkedHashMap<String, Object>();
            form.put("id", id);
            form.put(
                    "dealer",
                    id == 0
                            ? (portalMode() ? portalDealer.get("id") : value(request, "dealer"))
                            : order.get("dealer_id"));
            form.put("address", id == 0 ? "" : order.get("address_id"));
            form.put(
                    "delivery",
                    id == 0
                            ? LocalDate.now(vn.codegym.salesinventory.config.VietnamTime.ZONE)
                                    .plusDays(1)
                            : order.get("desired_delivery"));
            form.put("version", id == 0 ? 0 : order.get("version"));
            form.put(
                    "creationKey",
                    id == 0
                            ? UUID.randomUUID().toString()
                            : portalMode() ? "" : order.get("creation_key"));
            request.setAttribute("form", form);
        }
        @SuppressWarnings("unchecked")
        var form = (Map<String, Object>) request.getAttribute("form");
        long dealer = 0;
        try {
            dealer = Long.parseLong(Sql.text(form.get("dealer")));
        } catch (NumberFormatException ignored) {
        }
        if (dealer > 0) {
            request.setAttribute(
                    "selectedDealer", portalMode() ? portalDealer : dealers().find(actor, dealer));
            request.setAttribute("addresses", service().addresses(actor, dealer));
        }
        if (request.getAttribute("lineForms") == null) {
            request.setAttribute(
                    "lineForms",
                    id == 0
                            ? List.of(Map.of("product_id", "", "unit_id", "", "quantity", "1"))
                            : order.get("lines"));
        }
        request.setAttribute(
                "products",
                portalMode()
                        ? new PortalCatalogService(source())
                                .search(actor, "", new PageRequest(1, 20))
                                .items()
                        : products()
                                .search(actor, "", null, "ACTIVE", new PageRequest(1, 20))
                                .items());
        request.setAttribute("submitKey", UUID.randomUUID().toString());
        request.setAttribute("editing", id == 0 || Boolean.TRUE.equals(order.get("can_edit")));
        if (Boolean.TRUE.equals(order.get("can_review"))
                && request.getAttribute("approvalQuote") == null) {
            request.setAttribute("approvalQuote", service().reviewQuote(actor, id).reviewDisplay());
        }
        request.setAttribute(
                "deliveryToday",
                LocalDate.now(vn.codegym.salesinventory.config.VietnamTime.ZONE).toString());
        request.setAttribute("decisionKey", UUID.randomUUID().toString());
        if (Boolean.TRUE.equals(order.get("can_confirm_portal"))
                && request.getAttribute("confirmationQuote") == null) {
            request.setAttribute(
                    "confirmationQuote", service().confirmationQuote(actor, id).display());
        }
        if (quote && Boolean.TRUE.equals(order.get("can_edit"))) {
            request.setAttribute("quote", service().quote(actor, id).display());
        } else if (quote && "DRAFT".equals(order.get("status"))) {
            request.setAttribute("readQuote", service().estimate(actor, id).display());
        }
    }

    // Nạp dữ liệu và hiển thị trang đơn hàng.
    protected void get(HttpServletRequest request, HttpServletResponse response) throws Exception {
        layout(request);
        if (portalMode()) {
            new PortalAccountService(source(), null).profile(actor(request).id());
        }
        if ("/api/orders/options".equals(request.getServletPath())
                || "/api/portal/options".equals(request.getServletPath())) {
            long dealer = number(request, "dealer");
            if (value(request, "product").isEmpty()) {
                json(response, Map.of("items", service().addresses(actor(request).id(), dealer)));
            } else {
                json(
                        response,
                        Map.of(
                                "items",
                                service()
                                        .units(
                                                actor(request).id(),
                                                dealer,
                                                number(request, "product"))));
            }
            return;
        }
        if ("print".equals(value(request, "action"))) {
            access(request).require(permission(false));
            var order = service().find(actor(request).id(), number(request, "id"));
            if ("DRAFT".equals(order.get("status"))) {
                response.sendError(409, "Lưu và gửi đơn trước khi in chứng từ.");
                return;
            }
            request.setAttribute("order", order);
            view(request, response, "orders/print");
            return;
        }
        if ("/api/portal/products".equals(request.getServletPath())) {
            json(
                    response,
                    Map.of(
                            "items",
                            new PortalCatalogService(source())
                                    .suggest(actor(request).id(), value(request, "q"))));
            return;
        }
        if ("/portal".equals(request.getServletPath())) {
            request.setAttribute(
                    "dealer",
                    new PortalAccountService(source(), null).profile(actor(request).id()));
            var offers =
                    new PortalCatalogService(source())
                            .search(
                                    actor(request).id(),
                                    value(request, "q"),
                                    PageRequest.parse(
                                            value(request, "page"), value(request, "pageSize")));
            request.setAttribute("products", offers.items());
            request.setAttribute("pagination", offers);
            view(request, response, "portal/home");
            return;
        }
        long id = optional(request, "id");
        if (id > 0 || "new".equals(value(request, "action"))) {
            access(request).require(permission(id == 0));
            editor(request, id, true);
            view(request, response, "orders/editor");
            return;
        }
        Long dealer = optional(request, "dealer") == 0 ? null : optional(request, "dealer");
        LocalDate from = null, to = null;
        try {
            if (!value(request, "from").isEmpty()) {
                from = LocalDate.parse(value(request, "from"));
            }
            if (!value(request, "to").isEmpty()) {
                to = LocalDate.parse(value(request, "to"));
            }
        } catch (java.time.format.DateTimeParseException exception) {
            throw new IllegalArgumentException("Khoảng ngày tạo đơn không hợp lệ.");
        }
        var report =
                service()
                        .searchReport(
                                actor(request).id(),
                                new OrderService.Filter(
                                        value(request, "q"),
                                        value(request, "status"),
                                        dealer,
                                        value(request, "staff").isEmpty()
                                                ? null
                                                : number(request, "staff"),
                                        value(request, "territory").isEmpty()
                                                ? null
                                                : number(request, "territory"),
                                        from,
                                        to),
                                PageRequest.parse(
                                        value(request, "page"), value(request, "pageSize")));
        request.setAttribute("orderFilterOptions", service().filterOptions(actor(request).id()));
        request.setAttribute("orders", report.page().items());
        request.setAttribute("pagination", report.page());
        request.setAttribute(
                "filteredAmount",
                vn.codegym.salesinventory.service.MoneyDisplay.format(report.totalAmount()));
        view(request, response, "orders/list");
    }

    // Tiếp nhận và xử lý thay đổi đơn hàng.
    protected void post(HttpServletRequest request, HttpServletResponse response) throws Exception {
        layout(request);
        if (portalMode()) {
            new PortalAccountService(source(), null).profile(actor(request).id());
        }
        String action = value(request, "action");
        long id = optional(request, "id");
        if (Set.of("renew", "renewPortal", "approveRenewal").contains(action)) {
            if (portalMode()) {
                throw new SecurityException();
            }
            boolean approve = "approveRenewal".equals(action);
            access(request)
                    .require(
                            approve
                                    ? "ORDER_APPROVE"
                                    : "renewPortal".equals(action)
                                            ? "ORDER_CONFIRM_PORTAL"
                                            : "ORDER_WRITE");
            try {
                service()
                        .renewReservation(
                                actor(request).id(),
                                id,
                                number(request, "version"),
                                value(request, "decisionKey"),
                                approve,
                                value(request, "reason"));
                redirect(
                        request,
                        response,
                        path() + "?id=" + id + "&notice=renewed" + filters(request));
            } catch (IllegalArgumentException exception) {
                response.setStatus(
                        exception
                                        instanceof
                                        vn.codegym.salesinventory.validation
                                                .CommandConflictException
                                ? 409
                                : 400);
                request.setAttribute("errors", fieldErrors(exception));
                editor(request, id, false);
                view(request, response, "orders/editor");
            }
            return;
        }
        if ("cancel".equals(action)) {
            if (portalMode()) {
                throw new SecurityException();
            }
            access(request).require("ORDER_CANCEL");
            try {
                service()
                        .cancel(
                                actor(request).id(),
                                id,
                                number(request, "version"),
                                value(request, "decisionKey"),
                                value(request, "reason"));
                redirect(
                        request,
                        response,
                        path() + "?id=" + id + "&notice=cancelled" + filters(request));
            } catch (IllegalArgumentException e) {
                response.setStatus(
                        e instanceof vn.codegym.salesinventory.validation.CommandConflictException
                                ? 409
                                : 400);
                request.setAttribute("errors", fieldErrors(e));
                editor(request, id, false);
                view(request, response, "orders/editor");
            }
            return;
        }
        if ("returnEdit".equals(action) || "returnPortal".equals(action)) {
            if (portalMode()) {
                throw new SecurityException();
            }
            boolean staff = "returnPortal".equals(action);
            access(request).require(staff ? "ORDER_CONFIRM_PORTAL" : "ORDER_APPROVE");
            try {
                service()
                        .returnForEdit(
                                actor(request).id(),
                                id,
                                number(request, "version"),
                                value(request, "decisionKey"),
                                value(request, "reason"),
                                staff);
                redirect(
                        request,
                        response,
                        path() + "?id=" + id + "&notice=returned" + filters(request));
            } catch (IllegalArgumentException e) {
                response.setStatus(
                        e instanceof vn.codegym.salesinventory.validation.CommandConflictException
                                ? 409
                                : 400);
                request.setAttribute("errors", fieldErrors(e));
                editor(request, id, false);
                view(request, response, "orders/editor");
            }
            return;
        }
        if ("confirmPortal".equals(action)) {
            if (portalMode()) {
                throw new SecurityException();
            }
            access(request).require("ORDER_CONFIRM_PORTAL");
            try {
                var result =
                        service()
                                .confirmPortalOrder(
                                        actor(request).id(),
                                        id,
                                        number(request, "version"),
                                        value(request, "quoteToken"),
                                        value(request, "decisionKey"),
                                        "true".equals(value(request, "confirmed")));
                if (result.accepted()) {
                    redirect(
                            request,
                            response,
                            path()
                                    + "?id="
                                    + id
                                    + "&notice="
                                    + (result.submitted() ? "submitted" : "pending")
                                    + filters(request));
                } else {
                    request.setAttribute("confirmationQuote", result.quote().display());
                    editor(request, id, false);
                    view(request, response, "orders/editor");
                }
            } catch (IllegalArgumentException e) {
                response.setStatus(
                        e instanceof vn.codegym.salesinventory.validation.CommandConflictException
                                ? 409
                                : 400);
                request.setAttribute("errors", fieldErrors(e));
                editor(request, id, false);
                view(request, response, "orders/editor");
            }
            return;
        }
        if ("approve".equals(action) || "reject".equals(action)) {
            if (portalMode()) {
                throw new SecurityException();
            }
            access(request).require("ORDER_APPROVE");
            try {
                var result =
                        service()
                                .decide(
                                        actor(request).id(),
                                        id,
                                        number(request, "version"),
                                        value(request, "quoteToken"),
                                        value(request, "decisionKey"),
                                        "approve".equals(action),
                                        value(request, "reason"),
                                        "true".equals(value(request, "confirmed")));
                if (result.accepted()) {
                    redirect(
                            request,
                            response,
                            path()
                                    + "?id="
                                    + id
                                    + "&notice="
                                    + (result.submitted() ? "approved" : "rejected")
                                    + filters(request));
                } else {
                    request.setAttribute("approvalQuote", result.quote().reviewDisplay());
                    editor(request, id, false);
                    view(request, response, "orders/editor");
                }
            } catch (IllegalArgumentException e) {
                response.setStatus(
                        e instanceof vn.codegym.salesinventory.validation.CommandConflictException
                                ? 409
                                : 400);
                request.setAttribute("errors", fieldErrors(e));
                editor(request, id, false);
                view(request, response, "orders/editor");
            }
            return;
        }
        access(request).require(permission(true));
        if ("preview".equals(action)) {
            try {
                json(response, service().preview(actor(request).id(), input(request)).display());
            } catch (IllegalArgumentException e) {
                response.setStatus(400);
                json(response, Map.of("errors", fieldErrors(e)));
            }
            return;
        }
        try {
            switch (action) {
                case "save" -> {
                    long saved = service().save(actor(request).id(), id, input(request));
                    redirect(
                            request,
                            response,
                            path() + "?id=" + saved + "&notice=saved" + filters(request));
                }
                case "takeover" -> {
                    if (portalMode()) {
                        throw new SecurityException();
                    }
                    service()
                            .takeover(
                                    actor(request).id(),
                                    id,
                                    number(request, "version"),
                                    value(request, "reason"));
                    redirect(
                            request,
                            response,
                            path() + "?id=" + id + "&notice=taken-over" + filters(request));
                }
                case "submit" -> {
                    var result =
                            service()
                                    .submit(
                                            actor(request).id(),
                                            id,
                                            number(request, "version"),
                                            value(request, "quoteToken"),
                                            value(request, "submitKey"),
                                            "true".equals(value(request, "confirmed")));
                    if (result.accepted()) {
                        redirect(
                                request,
                                response,
                                path()
                                        + "?id="
                                        + id
                                        + "&notice="
                                        + (result.submitted() ? "submitted" : "pending")
                                        + filters(request));
                    } else {
                        editor(request, id, false);
                        request.setAttribute("quote", result.quote().display());
                        view(request, response, "orders/editor");
                    }
                }
                default -> response.sendError(400);
            }
        } catch (IllegalArgumentException e) {
            response.setStatus(
                    e instanceof vn.codegym.salesinventory.validation.CommandConflictException
                            ? 409
                            : 400);
            request.setAttribute("errors", fieldErrors(e));
            if ("save".equals(action)) {
                request.setAttribute(
                        "form",
                        formValues(
                                request,
                                "id",
                                "dealer",
                                "address",
                                "delivery",
                                "version",
                                "creationKey"));
                request.setAttribute("lineForms", lineValues(request));
                request.setAttribute("unsaved", true);
            }
            editor(request, id, false);
            view(request, response, "orders/editor");
        }
    }
}
