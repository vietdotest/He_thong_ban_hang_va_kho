package vn.codegym.salesinventory.controller;

import jakarta.servlet.http.*;
import vn.codegym.salesinventory.service.*;
import vn.codegym.salesinventory.dao.Sql;

/**
 * S2-05: Trả ảnh sản phẩm sau khi kiểm tra quyền và sản phẩm tương ứng.
 */
public final class ProductImageServlet extends PortalServlet {

    // Kiểm quyền xem sản phẩm và trả ảnh theo kích thước yêu cầu.
    protected void get(HttpServletRequest request, HttpServletResponse response) throws Exception {
        var product = new ProductService(source()).find(actor(request).id(), number(request, "id"));
        String key = Sql.text(product.get("image_key"));
        if (key.isEmpty()) {
            response.sendError(404);
            return;
        }
        var path = ImageStorage.configured().path(key, !value(request, "size").equals("full"));
        if (!java.nio.file.Files.isRegularFile(path)) {
            response.sendError(404);
            return;
        }
        response.setContentType("image/png");
        response.setHeader("Cache-Control", "private, max-age=300");
        java.nio.file.Files.copy(path, response.getOutputStream());
    }
}
