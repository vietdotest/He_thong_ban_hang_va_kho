package vn.codegym.salesinventory.controller;

import jakarta.servlet.http.*;
import java.math.BigDecimal;
import java.util.*;
import vn.codegym.salesinventory.service.FulfillmentService;
import vn.codegym.salesinventory.validation.CommandConflictException;

/**
 * S6-03, S6-05, S6-08 (MVP): Nhận thao tác soạn, xuất, giao và nhận lại hàng từ màn hình kho.
 */
public final class FulfillmentServlet extends PortalServlet {

    private FulfillmentService service() {
        return new FulfillmentService(source());
    }

    // Nạp dữ liệu soạn và giao hàng cho màn hình.
    private void load(HttpServletRequest request, long order) {
        request.setAttribute("orders", service().orders(actor(request).id()));
        request.setAttribute("shipments", service().shipments(actor(request).id()));
        if (order > 0) {
            request.setAttribute("order", service().order(actor(request).id(), order));
        }
        request.setAttribute("requestKey", UUID.randomUUID().toString());
    }

    // Nạp dữ liệu và hiển thị trang soạn và giao hàng.
    @Override
    protected void get(HttpServletRequest request, HttpServletResponse response) throws Exception {
        if (!value(request, "id").isEmpty()) {
            request.setAttribute(
                    "shipment", service().shipment(actor(request).id(), number(request, "id")));
        }
        load(request, value(request, "order").isEmpty() ? 0 : number(request, "order"));
        view(request, response, "fulfillment/shipments");
    }

    // Tiếp nhận và xử lý thay đổi soạn và giao hàng.
    @Override
    protected void post(HttpServletRequest request, HttpServletResponse response) throws Exception {
        if ("recover".equals(value(request, "action"))) {
            access(request).require("STOCK_RETURN_RECEIVE");
            long id = number(request, "id");
            try {
                String[] ids = request.getParameterValues("recoveryLine"),
                        good = request.getParameterValues("sellable"),
                        damaged = request.getParameterValues("damaged");
                if (ids == null
                        || good == null
                        || damaged == null
                        || ids.length != good.length
                        || ids.length != damaged.length) {
                    throw new IllegalArgumentException("Dòng nhận lại không hợp lệ.");
                }
                var lines = new ArrayList<FulfillmentService.RecoveryLine>();
                var values = new LinkedHashMap<String, Map<String, String>>();
                for (int i = 0; i < ids.length; i++) {
                    values.put(ids[i], Map.of("sellable", good[i], "damaged", damaged[i]));
                }
                request.setAttribute("recoveryValues", values);
                request.setAttribute("recoveryReason", value(request, "reason"));
                for (int i = 0; i < ids.length; i++) {
                    if (good[i].length() > 128 || damaged[i].length() > 128) {
                        throw new IllegalArgumentException("Số lượng không hợp lệ.");
                    }
                    long line = Long.parseLong(ids[i]);
                    BigDecimal sellable = new BigDecimal(good[i]),
                            broken = new BigDecimal(damaged[i]);
                    if (sellable.signum() != 0) {
                        lines.add(new FulfillmentService.RecoveryLine(line, sellable, "SELLABLE"));
                    }
                    if (broken.signum() != 0) {
                        lines.add(new FulfillmentService.RecoveryLine(line, broken, "DAMAGED"));
                    }
                }
                service()
                        .recover(
                                actor(request).id(),
                                id,
                                number(request, "version"),
                                value(request, "requestKey"),
                                lines,
                                value(request, "reason"));
                redirect(request, response, "/fulfillment/shipments?id=" + id + "&notice=saved");
            } catch (IllegalArgumentException exception) {
                response.setStatus(exception instanceof CommandConflictException ? 409 : 400);
                request.setAttribute("errors", fieldErrors(exception));
                request.setAttribute("shipment", service().shipment(actor(request).id(), id));
                load(request, 0);
                view(request, response, "fulfillment/shipments");
            }
            return;
        }
        access(request).require("FULFILLMENT_WRITE");
        if ("deliver".equals(value(request, "action"))) {
            long id = number(request, "id");
            try {
                String[] ids = request.getParameterValues("shipmentLine"),
                        quantities = request.getParameterValues("received");
                if (ids == null || quantities == null || ids.length != quantities.length) {
                    throw new IllegalArgumentException("Dòng giao không hợp lệ.");
                }
                var lines = new ArrayList<FulfillmentService.DeliveryLine>();
                var values = new LinkedHashMap<String, String>();
                for (int i = 0; i < ids.length; i++) {
                    values.put(ids[i], quantities[i]);
                }
                request.setAttribute("receivedValues", values);
                request.setAttribute("recipient", value(request, "recipient"));
                request.setAttribute("deliveryReason", value(request, "reason"));
                for (int i = 0; i < ids.length; i++) {
                    if (quantities[i].length() > 128) {
                        throw new IllegalArgumentException("Số lượng không hợp lệ.");
                    }
                    lines.add(
                            new FulfillmentService.DeliveryLine(
                                    Long.parseLong(ids[i]), new BigDecimal(quantities[i])));
                }
                service()
                        .deliver(
                                actor(request).id(),
                                id,
                                number(request, "version"),
                                value(request, "requestKey"),
                                value(request, "recipient"),
                                lines,
                                value(request, "reason"));
                redirect(request, response, "/fulfillment/shipments?id=" + id + "&notice=saved");
            } catch (IllegalArgumentException e) {
                response.setStatus(e instanceof CommandConflictException ? 409 : 400);
                request.setAttribute("errors", fieldErrors(e));
                request.setAttribute("shipment", service().shipment(actor(request).id(), id));
                load(request, 0);
                view(request, response, "fulfillment/shipments");
            }
            return;
        }
        long order = number(request, "order");
        try {
            switch (value(request, "action")) {
                case "pick" ->
                        service()
                                .startPicking(
                                        actor(request).id(),
                                        order,
                                        number(request, "version"),
                                        value(request, "requestKey"));
                case "dispatch" -> {
                    String[] ids = request.getParameterValues("line"),
                            quantities = request.getParameterValues("quantity");
                    if (ids == null
                            || quantities == null
                            || ids.length != quantities.length
                            || ids.length > 100) {
                        throw new IllegalArgumentException("Dòng xuất không hợp lệ.");
                    }
                    var lines = new ArrayList<FulfillmentService.DispatchLine>();
                    var values = new LinkedHashMap<String, String>();
                    for (int i = 0; i < ids.length; i++) {
                        values.put(ids[i], quantities[i]);
                    }
                    request.setAttribute("lineValues", values);
                    request.setAttribute("reason", value(request, "reason"));
                    for (int i = 0; i < ids.length; i++) {
                        if (quantities[i].length() > 128) {
                            throw new IllegalArgumentException("Số lượng không hợp lệ.");
                        }
                        BigDecimal quantity = new BigDecimal(quantities[i]);
                        if (quantity.signum() != 0) {
                            lines.add(
                                    new FulfillmentService.DispatchLine(
                                            Long.parseLong(ids[i]), quantity));
                        }
                    }
                    service()
                            .dispatch(
                                    actor(request).id(),
                                    order,
                                    number(request, "version"),
                                    value(request, "requestKey"),
                                    lines,
                                    value(request, "reason"));
                }
                default -> {
                    response.sendError(400);
                    return;
                }
            }
            redirect(request, response, "/fulfillment/shipments?order=" + order + "&notice=saved");
        } catch (IllegalArgumentException e) {
            response.setStatus(e instanceof CommandConflictException ? 409 : 400);
            request.setAttribute("errors", fieldErrors(e));
            load(request, order);
            view(request, response, "fulfillment/shipments");
        }
    }
}
