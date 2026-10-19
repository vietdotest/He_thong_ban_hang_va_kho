package vn.codegym.salesinventory.controller;

import jakarta.servlet.http.*;
import vn.codegym.salesinventory.service.DealerHandoverService;
import vn.codegym.salesinventory.dto.PageRequest;

/**
 * S3-06, hỗ trợ S1-10: Hiển thị bản xem trước, xác nhận và lịch sử bàn giao đại lý.
 */
public final class DealerHandoverServlet extends PortalServlet {

    private DealerHandoverService service() {
        return new DealerHandoverService(source());
    }

    // Nạp dữ liệu bàn giao đại lý cho màn hình.
    private void load(HttpServletRequest request) {
        service().options(actor(request).id()).forEach(request::setAttribute);
        var paging = PageRequest.parse(value(request, "page"), value(request, "pageSize"));
        if (!value(request, "batch").isEmpty()) {
            request.setAttribute(
                    "batch", service().batch(actor(request).id(), value(request, "batch")));
            var result = service().rows(actor(request).id(), value(request, "batch"), paging);
            request.setAttribute("rows", result.items());
            request.setAttribute("pagination", result);
        } else {
            var result = service().history(actor(request).id(), paging);
            request.setAttribute("history", result.items());
            request.setAttribute("pagination", result);
        }
    }

    private void selectedDealer(HttpServletRequest request) {
        if (!value(request, "dealerId").isEmpty()) {
            request.setAttribute(
                    "selectedDealer",
                    new vn.codegym.salesinventory.service.DealerService(source())
                            .find(actor(request).id(), number(request, "dealerId")));
        }
    }

    // Nạp dữ liệu và hiển thị trang bàn giao đại lý.
    protected void get(HttpServletRequest request, HttpServletResponse response) throws Exception {
        load(request);
        selectedDealer(request);
        request.setAttribute("form", formValues(request, "fromStaff", "dealerId", "territory"));
        view(request, response, "dealers/handover");
    }

    // Tiếp nhận và xử lý thay đổi bàn giao đại lý.
    protected void post(HttpServletRequest request, HttpServletResponse response) throws Exception {
        access(request).require("DEALER_HANDOVER");
        try {
            String key;
            if ("confirm".equals(value(request, "action"))) {
                key = value(request, "batch");
                service().confirm(actor(request).id(), key);
            } else {
                key =
                        service()
                                .preview(
                                        actor(request).id(),
                                        number(request, "fromStaff"),
                                        number(request, "toStaff"),
                                        value(request, "territory").isEmpty()
                                                ? null
                                                : number(request, "territory"),
                                        value(request, "reason"),
                                        value(request, "dealerId").isEmpty()
                                                ? null
                                                : number(request, "dealerId"));
            }
            redirect(request, response, "/dealers/handover?batch=" + key);
        } catch (IllegalArgumentException exception) {
            response.setStatus(400);
            request.setAttribute(
                    "form",
                    formValues(request, "fromStaff", "toStaff", "territory", "dealerId", "reason"));
            request.setAttribute("error", exception.getMessage());
            load(request);
            selectedDealer(request);
            view(request, response, "dealers/handover");
        }
    }
}
