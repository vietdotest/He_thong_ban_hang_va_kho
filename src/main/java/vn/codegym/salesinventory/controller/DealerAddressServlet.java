package vn.codegym.salesinventory.controller;

import jakarta.servlet.http.*;
import vn.codegym.salesinventory.service.*;
import vn.codegym.salesinventory.dto.PageRequest;

/**
 * S3-04: Hiển thị và xử lý các điểm giao hàng của đại lý.
 */
public final class DealerAddressServlet extends PortalServlet {

    private DealerAddressService service() {
        return new DealerAddressService(source());
    }

    // Nạp dữ liệu địa chỉ giao hàng của đại lý cho màn hình.
    private void load(HttpServletRequest request) {
        long dealer = number(request, "dealer");
        request.setAttribute(
                "dealer", new DealerService(source()).find(actor(request).id(), dealer));
        var p =
                service()
                        .list(
                                actor(request).id(),
                                dealer,
                                value(request, "q"),
                                PageRequest.parse(
                                        value(request, "page"), value(request, "pageSize")));
        request.setAttribute("addresses", p.items());
        request.setAttribute("pagination", p);
    }

    // Nạp dữ liệu và hiển thị trang địa chỉ giao hàng của đại lý.
    protected void get(HttpServletRequest request, HttpServletResponse response) throws Exception {
        load(request);
        if (!value(request, "id").isEmpty()) {
            request.setAttribute(
                    "edit",
                    service()
                            .find(
                                    actor(request).id(),
                                    number(request, "dealer"),
                                    number(request, "id")));
        }
        view(request, response, "dealers/addresses");
    }

    // Tiếp nhận và xử lý thay đổi địa chỉ giao hàng của đại lý.
    protected void post(HttpServletRequest request, HttpServletResponse response) throws Exception {
        access(request).require("DEALER_ADDRESS_MANAGE");
        var form =
                formValues(
                        request,
                        "id",
                        "version",
                        "address",
                        "recipient",
                        "phone",
                        "directions",
                        "is_default",
                        "status");
        form.put("is_default", "1".equals(value(request, "is_default")));
        try {
            long dealer = number(request, "dealer"),
                    id = value(request, "id").isEmpty() ? 0 : number(request, "id");
            service()
                    .save(
                            actor(request).id(),
                            dealer,
                            id,
                            new DealerAddressService.Input(
                                    value(request, "address"),
                                    value(request, "recipient"),
                                    value(request, "phone"),
                                    value(request, "directions"),
                                    "1".equals(value(request, "is_default")),
                                    value(request, "status"),
                                    value(request, "version").isEmpty()
                                            ? 0
                                            : number(request, "version"),
                                    number(request, "dealerVersion")));
            redirect(request, response, "/dealers/addresses?dealer=" + dealer + "&notice=saved");
        } catch (IllegalArgumentException exception) {
            response.setStatus(400);
            request.setAttribute("edit", form);
            request.setAttribute("errors", fieldErrors(exception));
            load(request);
            view(request, response, "dealers/addresses");
        }
    }
}
