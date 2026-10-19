package vn.codegym.salesinventory.controller;

import jakarta.servlet.http.*;
import java.util.UUID;
import vn.codegym.salesinventory.service.InvoiceService;
import vn.codegym.salesinventory.validation.CommandConflictException;

/**
 * S7-01 (MVP): Hiển thị, phát hành và in hóa đơn từ giao hàng đã ghi nhận.
 */
public final class InvoiceServlet extends PortalServlet {

    private InvoiceService service() {
        return new InvoiceService(source());
    }

    // Nạp dữ liệu hóa đơn cho màn hình.
    private void load(HttpServletRequest request, long id) {
        request.setAttribute("invoices", service().invoices(actor(request).id()));
        request.setAttribute("requestKey", UUID.randomUUID().toString());
        if (id > 0) {
            request.setAttribute("invoice", service().invoice(actor(request).id(), id));
        }
        if (access(request).allows("INVOICE_ISSUE")) {
            var deliveries = service().availableDeliveries(actor(request).id());
            for (var delivery : deliveries) {
                delivery.put("request_key", UUID.randomUUID().toString());
            }
            request.setAttribute("deliveries", deliveries);
        }
    }

    // Nạp dữ liệu và hiển thị trang hóa đơn.
    @Override
    protected void get(HttpServletRequest request, HttpServletResponse response) throws Exception {
        load(request, value(request, "id").isEmpty() ? 0 : number(request, "id"));
        view(request, response, "accounting/invoices");
    }

    // Tiếp nhận và xử lý thay đổi hóa đơn.
    @Override
    protected void post(HttpServletRequest request, HttpServletResponse response) throws Exception {
        access(request).require("INVOICE_ISSUE");
        if (!"issue".equals(value(request, "action"))) {
            response.sendError(400);
            return;
        }
        try {
            long id =
                    service()
                            .issue(
                                    actor(request).id(),
                                    number(request, "shipment"),
                                    number(request, "version"),
                                    value(request, "requestKey"));
            redirect(request, response, "/accounting/invoices?id=" + id + "&notice=issued");
        } catch (IllegalArgumentException exception) {
            response.setStatus(exception instanceof CommandConflictException ? 409 : 400);
            request.setAttribute("errors", fieldErrors(exception));
            load(request, 0);
            view(request, response, "accounting/invoices");
        }
    }
}
