package vn.codegym.salesinventory.controller;

import jakarta.servlet.http.*;
import vn.codegym.salesinventory.service.StockService;

/**
 * S5-05: Hiển thị tồn thực tế, giữ chỗ và khả dụng theo kho, sản phẩm.
 */
public final class StockServlet extends PortalServlet {

    // Nạp dữ liệu và hiển thị trang tồn kho.
    @Override
    protected void get(HttpServletRequest request, HttpServletResponse response) throws Exception {
        var service = new StockService(source());
        Long warehouse =
                value(request, "warehouse").isEmpty() ? null : number(request, "warehouse");
        request.setAttribute("warehouses", service.warehouses(actor(request).id(), "STOCK_READ"));
        request.setAttribute(
                "stock",
                service.stock(
                        actor(request).id(),
                        warehouse,
                        value(request, "q"),
                        value(request, "category").isEmpty() ? null : number(request, "category"),
                        value(request, "state")));
        request.setAttribute(
                "categories",
                new vn.codegym.salesinventory.service.CategoryService(source()).tree());
        view(request, response, "inventory/stock");
    }

    // Tiếp nhận và xử lý thay đổi tồn kho.
    @Override
    protected void post(HttpServletRequest request, HttpServletResponse response) throws Exception {
        response.sendError(405);
    }
}
