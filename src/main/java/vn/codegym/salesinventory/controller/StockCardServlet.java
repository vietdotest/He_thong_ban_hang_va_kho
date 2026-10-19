package vn.codegym.salesinventory.controller;

import jakarta.servlet.http.*;
import vn.codegym.salesinventory.service.StockService;

/**
 * S6-02 (MVP): Hiển thị thẻ kho với các phát sinh của sản phẩm trong kho được phép xem.
 */
public final class StockCardServlet extends PortalServlet {

    // Nạp dữ liệu và hiển thị trang thẻ kho.
    @Override
    protected void get(HttpServletRequest request, HttpServletResponse response) throws Exception {
        long warehouse = number(request, "warehouse"), product = number(request, "product");
        var service = new StockService(source());
        var from = date(request, "from");
        var to = date(request, "to");
        request.setAttribute(
                "cardHeader", service.cardHeader(actor(request).id(), warehouse, product));
        request.setAttribute(
                "events", service.card(actor(request).id(), warehouse, product, from, to));
        view(request, response, "inventory/card");
    }

    // Đọc ngày lọc thẻ kho và kiểm tra định dạng.
    private static java.time.LocalDate date(HttpServletRequest request, String name) {
        String value = request.getParameter(name);
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return java.time.LocalDate.parse(value);
        } catch (java.time.format.DateTimeParseException exception) {
            throw new IllegalArgumentException("Ngày lọc thẻ kho không hợp lệ.");
        }
    }

    // Tiếp nhận và xử lý thay đổi thẻ kho.
    @Override
    protected void post(HttpServletRequest request, HttpServletResponse response) throws Exception {
        response.sendError(405);
    }
}
