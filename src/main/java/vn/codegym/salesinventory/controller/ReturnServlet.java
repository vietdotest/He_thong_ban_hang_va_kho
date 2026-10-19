package vn.codegym.salesinventory.controller;

import jakarta.servlet.http.*;
import java.math.BigDecimal;
import java.util.*;
import vn.codegym.salesinventory.dao.Sql;
import vn.codegym.salesinventory.service.ReturnService;
import vn.codegym.salesinventory.validation.CommandConflictException;

/**
 * S8-01, S8-02 (MVP): Nhận yêu cầu trả hàng và hiển thị chứng từ giảm trừ công nợ.
 */
public final class ReturnServlet extends PortalServlet {

    private ReturnService service() {
        return new ReturnService(source());
    }

    private static long optional(HttpServletRequest request, String name) {
        return value(request, name).isEmpty() ? 0 : number(request, name);
    }

    // Nạp dữ liệu trả hàng và giảm trừ công nợ cho màn hình.
    private void load(HttpServletRequest request, long id, long invoice) {
        request.setAttribute("returns", service().returns(actor(request).id()));
        request.setAttribute("requestKey", UUID.randomUUID().toString());
        Map<String, Object> document =
                id == 0 ? Map.of() : service().document(actor(request).id(), id);
        request.setAttribute("document", document);
        if (access(request).allows("RETURN_WRITE")
                && access(request).roles().stream()
                        .anyMatch(Set.of("SALES", "SALES_MANAGER")::contains)) {
            request.setAttribute("invoices", service().invoicesForReturn(actor(request).id()));
        }
        if (Boolean.TRUE.equals(document.get("can_edit"))) {
            invoice = Sql.id(document.get("invoice_id"));
        } else if (id > 0) {
            invoice = 0;
        }
        if (invoice > 0) {
            var original = service().invoiceForReturn(actor(request).id(), invoice, id);
            request.setAttribute("sourceInvoice", original);
            if (request.getAttribute("lineValues") == null) {
                var values = new LinkedHashMap<String, Map<String, String>>();
                @SuppressWarnings("unchecked")
                var sourceLines = (List<Map<String, Object>>) original.get("lines");
                for (var line : sourceLines) {
                    values.put(
                            line.get("id").toString(),
                            new LinkedHashMap<>(Map.of("sellable", "0", "damaged", "0")));
                }
                if (!document.isEmpty()) {
                    @SuppressWarnings("unchecked")
                    var lines = (List<Map<String, Object>>) document.get("lines");
                    for (var line : lines) {
                        values.get(line.get("invoice_line_id").toString())
                                .put(
                                        "SELLABLE".equals(line.get("condition_code"))
                                                ? "sellable"
                                                : "damaged",
                                        line.get("quantity").toString());
                    }
                }
                request.setAttribute("lineValues", values);
            }
        }
        if (request.getAttribute("reason") == null) {
            request.setAttribute("reason", Sql.text(document.get("reason")));
        }
    }

    // Nạp dữ liệu và hiển thị trang trả hàng và giảm trừ công nợ.
    @Override
    protected void get(HttpServletRequest request, HttpServletResponse response) throws Exception {
        load(request, optional(request, "id"), optional(request, "invoice"));
        view(request, response, "accounting/returns");
    }

    // Tiếp nhận và xử lý thay đổi trả hàng và giảm trừ công nợ.
    @Override
    protected void post(HttpServletRequest request, HttpServletResponse response) throws Exception {
        String action = value(request, "action");
        long id = optional(request, "id"), invoice = optional(request, "invoice");
        try {
            switch (action) {
                case "save" -> {
                    access(request).require("RETURN_WRITE");
                    String[] ids = request.getParameterValues("invoiceLine"),
                            good = request.getParameterValues("sellable"),
                            damaged = request.getParameterValues("damaged");
                    if (ids == null
                            || good == null
                            || damaged == null
                            || ids.length != good.length
                            || ids.length != damaged.length) {
                        throw new IllegalArgumentException("Dòng trả không hợp lệ.");
                    }
                    var lines = new ArrayList<ReturnService.Line>();
                    var values = new LinkedHashMap<String, Map<String, String>>();
                    for (int i = 0; i < ids.length; i++) {
                        values.put(ids[i], Map.of("sellable", good[i], "damaged", damaged[i]));
                    }
                    request.setAttribute("lineValues", values);
                    request.setAttribute("reason", value(request, "reason"));
                    for (int i = 0; i < ids.length; i++) {
                        if (good[i].length() > 128 || damaged[i].length() > 128) {
                            throw new IllegalArgumentException("Số lượng không hợp lệ.");
                        }
                        long line = Long.parseLong(ids[i]);
                        BigDecimal sellable = new BigDecimal(good[i]),
                                broken = new BigDecimal(damaged[i]);
                        if (sellable.signum() != 0) {
                            lines.add(new ReturnService.Line(line, sellable, "SELLABLE"));
                        }
                        if (broken.signum() != 0) {
                            lines.add(new ReturnService.Line(line, broken, "DAMAGED"));
                        }
                    }
                    id =
                            service()
                                    .save(
                                            actor(request).id(),
                                            id,
                                            number(request, "version"),
                                            value(request, "requestKey"),
                                            new ReturnService.Input(
                                                    invoice, value(request, "reason"), lines));
                }
                case "receive" -> {
                    access(request).require("STOCK_RETURN_RECEIVE");
                    service()
                            .receive(
                                    actor(request).id(),
                                    id,
                                    number(request, "version"),
                                    value(request, "requestKey"),
                                    "true".equals(value(request, "physicallyConfirmed")));
                }
                case "credit" -> {
                    access(request).require("CREDIT_ISSUE");
                    service()
                            .issueCredit(
                                    actor(request).id(),
                                    id,
                                    number(request, "version"),
                                    value(request, "requestKey"));
                }
                case "cancel" -> {
                    access(request).require("RETURN_WRITE");
                    service()
                            .cancel(
                                    actor(request).id(),
                                    id,
                                    number(request, "version"),
                                    value(request, "requestKey"),
                                    value(request, "reason"));
                }
                default -> {
                    response.sendError(400);
                    return;
                }
            }
            redirect(request, response, "/accounting/returns?id=" + id + "&notice=saved");
        } catch (IllegalArgumentException exception) {
            response.setStatus(exception instanceof CommandConflictException ? 409 : 400);
            request.setAttribute("errors", fieldErrors(exception));
            load(request, id, invoice);
            view(request, response, "accounting/returns");
        }
    }
}
