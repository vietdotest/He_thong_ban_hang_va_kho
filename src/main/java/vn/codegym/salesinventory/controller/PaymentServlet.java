package vn.codegym.salesinventory.controller;

import jakarta.servlet.http.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import vn.codegym.salesinventory.dao.Sql;
import vn.codegym.salesinventory.service.PaymentService;
import vn.codegym.salesinventory.validation.CommandConflictException;

/**
 * S7-02, S7-09 (MVP): Nhận ghi thu, đảo phiếu và hiển thị phiếu thu để in.
 */
public final class PaymentServlet extends PortalServlet {

    private PaymentService service() {
        return new PaymentService(source());
    }

    // Nạp dữ liệu phiếu thu cho màn hình.
    private void load(HttpServletRequest request, long id, long dealer) {
        request.setAttribute("payments", service().payments(actor(request).id()));
        request.setAttribute("requestKey", UUID.randomUUID().toString());
        if (id > 0) {
            request.setAttribute("payment", service().payment(actor(request).id(), id));
        }
        if (access(request).allows("PAYMENT_WRITE")) {
            var debtors = service().debtors(actor(request).id());
            request.setAttribute("debtors", debtors);
            for (var customer : debtors) {
                if (Sql.id(customer.get("id")) == dealer) {
                    request.setAttribute("selectedDealer", customer);
                }
            }
        }
        if (request.getAttribute("form") == null) {
            request.setAttribute(
                    "form",
                    Map.of(
                            "amount",
                            "",
                            "date",
                            LocalDate.now(vn.codegym.salesinventory.config.VietnamTime.ZONE)
                                    .toString(),
                            "method",
                            "CASH",
                            "note",
                            ""));
        }
    }

    // Nạp dữ liệu và hiển thị trang phiếu thu.
    @Override
    protected void get(HttpServletRequest request, HttpServletResponse response) throws Exception {
        load(
                request,
                value(request, "id").isEmpty() ? 0 : number(request, "id"),
                value(request, "dealer").isEmpty() ? 0 : number(request, "dealer"));
        view(request, response, "accounting/payments");
    }

    // Tiếp nhận và xử lý thay đổi phiếu thu.
    @Override
    protected void post(HttpServletRequest request, HttpServletResponse response) throws Exception {
        access(request).require("PAYMENT_WRITE");
        if ("reverse".equals(value(request, "action"))) {
            long id = number(request, "id");
            try {
                service()
                        .reverse(
                                actor(request).id(),
                                id,
                                number(request, "version"),
                                value(request, "requestKey"),
                                value(request, "reason"));
                redirect(request, response, "/accounting/payments?id=" + id + "&notice=reversed");
            } catch (IllegalArgumentException exception) {
                response.setStatus(exception instanceof CommandConflictException ? 409 : 400);
                request.setAttribute("errors", fieldErrors(exception));
                request.setAttribute("reversalReason", value(request, "reason"));
                load(request, id, 0);
                view(request, response, "accounting/payments");
            }
            return;
        }
        if (!"record".equals(value(request, "action"))) {
            response.sendError(400);
            return;
        }
        long dealer = number(request, "dealer");
        try {
            if (value(request, "amount").length() > 128) {
                throw new IllegalArgumentException("Số tiền không hợp lệ.");
            }
            var input =
                    new PaymentService.Input(
                            new BigDecimal(value(request, "amount")),
                            LocalDate.parse(value(request, "date")),
                            value(request, "method"),
                            value(request, "note"));
            long id =
                    service()
                            .record(
                                    actor(request).id(),
                                    dealer,
                                    number(request, "version"),
                                    value(request, "requestKey"),
                                    input);
            redirect(request, response, "/accounting/payments?id=" + id + "&notice=recorded");
        } catch (IllegalArgumentException | java.time.format.DateTimeParseException e) {
            response.setStatus(e instanceof CommandConflictException ? 409 : 400);
            request.setAttribute(
                    "errors",
                    e instanceof IllegalArgumentException argument
                            ? fieldErrors(argument)
                            : Map.of("date", "Ngày thu không hợp lệ."));
            request.setAttribute("form", formValues(request, "amount", "date", "method", "note"));
            load(request, 0, dealer);
            view(request, response, "accounting/payments");
        }
    }
}
