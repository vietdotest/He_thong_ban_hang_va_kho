package vn.codegym.salesinventory.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import vn.codegym.salesinventory.service.AuditService;
import vn.codegym.salesinventory.service.PurchaseHistoryService;

/** S4-04: Trả lịch sử mua ba tháng cho màn hình lập đơn nội bộ. */
public final class PurchaseHistoryServlet extends PortalServlet {
    private final PurchaseHistoryService configured;

    public PurchaseHistoryServlet() {
        this(null);
    }

    PurchaseHistoryServlet(PurchaseHistoryService configured) {
        this.configured = configured;
    }

    // Kiểm tra yêu cầu và chỉ trả các dữ liệu gợi ý đã được service giới hạn phạm vi.
    @Override
    protected void get(HttpServletRequest request, HttpServletResponse response) throws Exception {
        response.setHeader("Cache-Control", "no-store");
        access(request).require("ORDER_WRITE");
        var service = configured == null ? new PurchaseHistoryService(source()) : configured;
        var history = service.load(actor(request).id(), number(request, "dealer"));
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(AuditService.snapshot(history.display(), false));
    }
}
