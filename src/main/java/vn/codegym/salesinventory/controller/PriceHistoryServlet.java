package vn.codegym.salesinventory.controller;

import jakarta.servlet.http.*;
import java.time.LocalDate;
import vn.codegym.salesinventory.dto.PageRequest;
import vn.codegym.salesinventory.service.*;
import vn.codegym.salesinventory.validation.FieldValidationException;

/**
 * S3-02: Hiển thị lịch sử thay đổi giá của sản phẩm.
 */
public final class PriceHistoryServlet extends PortalServlet {

    private PriceHistoryService service() {
        return new PriceHistoryService(source());
    }

    private Long id(HttpServletRequest request, String key) {
        return value(request, key).isEmpty() ? null : number(request, key);
    }

    // Đọc ngày lọc và báo lỗi khi định dạng ngày không hợp lệ.
    private LocalDate date(HttpServletRequest request, String key) {
        if (value(request, key).isEmpty()) {
            return null;
        }
        try {
            return LocalDate.parse(value(request, key));
        } catch (java.time.DateTimeException exception) {
            throw FieldValidationException.field(key, "Ngày lọc không hợp lệ.");
        }
    }

    // Nạp dữ liệu và hiển thị trang lịch sử giá.
    protected void get(HttpServletRequest request, HttpServletResponse response) throws Exception {
        access(request).require("PRICE_READ");
        request.setAttribute("groups", new PricingService(source()).groups());
        try {
            var result =
                    service()
                            .search(
                                    actor(request).id(),
                                    new PriceHistoryService.Filter(
                                            value(request, "q"),
                                            id(request, "product"),
                                            id(request, "group"),
                                            date(request, "from"),
                                            date(request, "to")),
                                    PageRequest.parse(
                                            value(request, "page"), value(request, "pageSize")));
            request.setAttribute("history", result.items());
            request.setAttribute("pagination", result);
        } catch (IllegalArgumentException exception) {
            response.setStatus(400);
            request.setAttribute("errors", fieldErrors(exception));
        }
        view(request, response, "pricing/history");
    }
}
