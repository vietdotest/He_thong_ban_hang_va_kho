package vn.codegym.salesinventory.controller;

import jakarta.servlet.http.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import vn.codegym.salesinventory.dao.Sql;
import vn.codegym.salesinventory.service.*;
import vn.codegym.salesinventory.validation.CommandConflictException;

/**
 * S5-04: Nhận phiếu nhập hàng từ nhà cung cấp vào kho.
 */
public final class StockReceiptServlet extends PortalServlet {

    private StockService service() {
        return new StockService(source());
    }

    private static long optional(HttpServletRequest request, String name) {
        return value(request, name).isEmpty() ? 0 : number(request, name);
    }

    // Đọc mã phiếu nhập từ chuỗi và xử lý giá trị không hợp lệ.
    private static String safeId(String raw) {
        try {
            return Long.parseLong(raw) > 0 ? raw : "";
        } catch (Exception exception) {
            return "";
        }
    }

    private static String cell(String[] values, int index) {
        return values != null && index < values.length ? values[index] : "";
    }

    // Nạp dữ liệu và hiển thị trang phiếu nhập kho.
    @Override
    protected void get(HttpServletRequest request, HttpServletResponse response) throws Exception {
        long actor = actor(request).id(), id = optional(request, "id");
        request.setAttribute("receipts", service().receipts(actor));
        request.setAttribute("requestKey", UUID.randomUUID().toString());
        if (id > 0) {
            request.setAttribute("receipt", service().receipt(actor, id));
        }
        if (!access(request).allows("STOCK_RECEIPT_WRITE")) {
            if ("new".equals(value(request, "action"))) {
                throw new SecurityException();
            }
            view(request, response, "inventory/receipts");
            return;
        }
        request.setAttribute("warehouses", service().warehouses(actor, "STOCK_RECEIPT_WRITE"));
        Sql.snapshot(
                source(),
                connection -> {
                    MvpAccess.internal(connection, actor, "STOCK_RECEIPT_WRITE");
                    request.setAttribute(
                            "suppliers",
                            Sql.query(
                                    connection,
                                    "SELECT s.id,s.code,s.name,s.warehouse_id FROM suppliers s"
                                        + " WHERE s.status='ACTIVE' AND EXISTS(SELECT 1 FROM"
                                        + " user_warehouses uw WHERE uw.user_id=? AND"
                                        + " uw.warehouse_id=s.warehouse_id) ORDER BY s.name,s.id",
                                    actor));
                    request.setAttribute(
                            "products",
                            Sql.query(
                                    connection,
                                    "SELECT id,sku,name FROM products WHERE status='ACTIVE' ORDER"
                                            + " BY sku LIMIT 500"));
                    request.setAttribute(
                            "units",
                            Sql.query(
                                    connection,
                                    "SELECT id,product_id,name,warehouse_id FROM product_units"
                                        + " WHERE warehouse_id IS NULL OR EXISTS(SELECT 1 FROM"
                                        + " user_warehouses uw WHERE uw.user_id=? AND"
                                        + " uw.warehouse_id=product_units.warehouse_id) ORDER BY"
                                        + " is_base DESC,name,id",
                                    actor));
                    return null;
                });
        if (request.getAttribute("form") == null) {
            var row = id > 0 ? service().receipt(actor, id) : Map.<String, Object>of();
            var form = new LinkedHashMap<>(row);
            form.put("id", id);
            form.putIfAbsent("version", 0);
            form.putIfAbsent(
                    "receipt_date",
                    LocalDate.now(vn.codegym.salesinventory.config.VietnamTime.ZONE).toString());
            request.setAttribute("form", form);
            if (id > 0) {
                request.setAttribute("lineForms", row.get("lines"));
            }
        }
        if (request.getAttribute("lineForms") == null) {
            request.setAttribute("lineForms", List.of(Map.of("quantity", "1")));
        }
        view(request, response, "inventory/receipts");
    }

    // Tiếp nhận và xử lý thay đổi phiếu nhập kho.
    @Override
    protected void post(HttpServletRequest request, HttpServletResponse response) throws Exception {
        access(request).require("STOCK_RECEIPT_WRITE");
        long id = optional(request, "id");
        try {
            switch (value(request, "action")) {
                case "save" -> {
                    String[] p = request.getParameterValues("product"),
                            u = request.getParameterValues("unit"),
                            q = request.getParameterValues("quantity"),
                            lots = request.getParameterValues("lot"),
                            expiry = request.getParameterValues("expiry");
                    if (p == null
                            || p.length == 0
                            || p.length > 100
                            || u == null
                            || q == null
                            || lots == null
                            || expiry == null
                            || u.length != p.length
                            || q.length != p.length
                            || lots.length != p.length
                            || expiry.length != p.length) {
                        throw new IllegalArgumentException("Dòng nhập không đầy đủ.");
                    }
                    var lines = new ArrayList<StockService.ReceiptLine>();
                    for (int i = 0; i < p.length; i++) {
                        if (q[i].length() > 128) {
                            throw new IllegalArgumentException("Số lượng không hợp lệ.");
                        }
                        lines.add(
                                new StockService.ReceiptLine(
                                        Long.parseLong(p[i]),
                                        Long.parseLong(u[i]),
                                        new BigDecimal(q[i]),
                                        lots[i],
                                        expiry[i].isBlank() ? null : LocalDate.parse(expiry[i])));
                    }
                    id =
                            service()
                                    .saveReceipt(
                                            actor(request).id(),
                                            id,
                                            optional(request, "version"),
                                            value(request, "requestKey"),
                                            new StockService.ReceiptInput(
                                                    number(request, "warehouse"),
                                                    number(request, "supplier"),
                                                    value(request, "reference"),
                                                    LocalDate.parse(value(request, "date")),
                                                    lines));
                }
                case "confirm" ->
                        service()
                                .confirmReceipt(
                                        actor(request).id(),
                                        id,
                                        number(request, "version"),
                                        value(request, "requestKey"));
                default -> throw new IllegalArgumentException("Thao tác không hợp lệ.");
            }
            redirect(request, response, "/inventory/receipts?id=" + id + "&notice=saved");
        } catch (IllegalArgumentException | java.time.format.DateTimeParseException exception) {
            response.setStatus(exception instanceof CommandConflictException ? 409 : 400);
            request.setAttribute(
                    "errors",
                    exception instanceof IllegalArgumentException argument
                            ? fieldErrors(argument)
                            : Map.of("date", "Ngày nhập hoặc hạn sử dụng không hợp lệ."));
            var f = new LinkedHashMap<String, Object>();
            f.put("id", id);
            f.put("version", value(request, "version"));
            f.put("warehouse_id", safeId(value(request, "warehouse")));
            f.put("supplier_id", safeId(value(request, "supplier")));
            f.put("document_ref", value(request, "reference"));
            f.put("receipt_date", value(request, "date"));
            request.setAttribute("form", f);
            String[] p = request.getParameterValues("product"),
                    u = request.getParameterValues("unit"),
                    q = request.getParameterValues("quantity"),
                    l = request.getParameterValues("lot"),
                    x = request.getParameterValues("expiry");
            if (p != null) {
                var forms = new ArrayList<Map<String, Object>>();
                for (int i = 0; i < Math.min(p.length, 100); i++) {
                    forms.add(
                            Map.of(
                                    "product_id",
                                    safeId(p[i]),
                                    "unit_id",
                                    safeId(cell(u, i)),
                                    "quantity",
                                    cell(q, i),
                                    "lot_code",
                                    cell(l, i),
                                    "expires_on",
                                    cell(x, i)));
                }
                request.setAttribute("lineForms", forms);
            }
            get(request, response);
        }
    }
}
