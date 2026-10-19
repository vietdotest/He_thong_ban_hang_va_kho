package vn.codegym.salesinventory.controller;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.*;
import vn.codegym.salesinventory.service.*;

/**
 * S2-08: Kết nối luồng nhập Excel dùng chung với nghiệp vụ nhập sản phẩm.
 */
public final class ProductImportServlet extends DurableImportServlet {

    private final ProductImportService configured;

    public ProductImportServlet() {
        configured = null;
    }

    ProductImportServlet(ProductImportService configured) {
        this.configured = configured;
    }

    @Override
    protected String importKind() {
        return "PRODUCT";
    }

    // Đọc tệp tải lên và tạo dữ liệu xem trước.
    @Override
    protected ImportPreview readPreview(long actor, byte[] bytes) {
        return service().preview(actor, bytes);
    }

    private ProductImportService service() {
        return configured == null ? new ProductImportService(source()) : configured;
    }

    // Nạp dữ liệu và hiển thị trang danh mục sản phẩm từ Excel.
    protected void get(HttpServletRequest request, HttpServletResponse response) throws Exception {
        if (configured == null) {
            super.get(request, response);
            return;
        }
        access(request).require("PRODUCT_MANAGE");
        if (value(request, "report").equals("1")) {
            ImportFlow.download(
                    request, response, "productImport", actor(request).id(), access(request));
            return;
        }
        if (value(request, "new").equals("1")) {
            ImportFlow.clear(request, "productImport");
        }
        if (value(request, "template").equals("1")) {
            var sample =
                    new ArrayList<>(
                            List.of(
                                    "SP-001",
                                    "Sản phẩm mẫu",
                                    "MA-NHOM",
                                    "Cái",
                                    "Thùng 12 cái",
                                    "ACTIVE"));
            if (access(request).allows("COST_WRITE")) {
                sample.add("10000");
            }
            response.setContentType(
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
            response.setHeader("Content-Disposition", "attachment; filename=san-pham.xlsx");
            response.getOutputStream()
                    .write(
                            Xlsx.write(
                                    List.of(
                                            ProductImportService.headers(access(request)),
                                            sample)));
            return;
        }
        Object report = request.getSession().getAttribute("productImportReport"),
                preview = request.getSession().getAttribute("productImport");
        if (preview instanceof ImportPreview pending
                && pending.available(actor(request).id(), java.time.Instant.now())) {
            ImportFlow.preview(
                    request,
                    "productImport",
                    pending,
                    ImportFlow.filename(
                            (String) request.getSession().getAttribute("productImportFile")));
        } else if (report instanceof ImportReport saved) {
            ImportFlow.show(request, saved, actor(request).id(), access(request));
        } else {
            request.getSession().removeAttribute("productImport");
        }
        render(request, response);
    }

    // Tiếp nhận và xử lý thay đổi danh mục sản phẩm từ Excel.
    protected void post(HttpServletRequest request, HttpServletResponse response) throws Exception {
        if (configured == null) {
            super.post(request, response);
            return;
        }
        access(request).require("PRODUCT_MANAGE");
        boolean confirm = value(request, "action").equals("confirm");
        try {
            if (confirm) {
                var preview = (ImportPreview) request.getSession().getAttribute("productImport");
                if (preview == null) {
                    throw new IllegalArgumentException("Hãy tải lên tệp trước.");
                }
                try {
                    var result =
                            service()
                                    .confirm(actor(request).id(), preview, value(request, "token"));
                    var headers = new ArrayList<>(ProductImportService.HEADERS);
                    if (preview.hasCostColumn()) {
                        headers.add("Giá vốn");
                    }
                    ImportFlow.result(
                            request,
                            "productImport",
                            actor(request).id(),
                            false,
                            headers,
                            result,
                            access(request));
                } finally {
                    request.getSession().removeAttribute("productImport");
                }
            } else {
                ImportFlow.clear(request, "productImport");
                var file = uploadFile(request);
                if (file == null || file.getSize() == 0) {
                    throw new IllegalArgumentException("Vui lòng chọn tệp Excel (.xlsx).");
                }
                if (file.getSize() > Xlsx.MAX_BYTES) {
                    throw new IllegalArgumentException("Tệp XLSX tối đa 10MB.");
                }
                byte[] bytes;
                try (var input = file.getInputStream()) {
                    bytes = input.readNBytes(Xlsx.MAX_BYTES + 1);
                }
                var preview = service().preview(actor(request).id(), bytes);
                ImportFlow.preview(
                        request,
                        "productImport",
                        preview,
                        ImportFlow.filename(file.getSubmittedFileName()));
            }
        } catch (IllegalArgumentException invalid) {
            response.setStatus(400);
            request.setAttribute(
                    "errors", Map.of(confirm ? "preview" : "file", invalid.getMessage()));
        }
        render(request, response);
    }

    // Hiển thị lại dữ liệu và thông báo khi yêu cầu không hợp lệ.
    @Override
    protected void badRequest(
            HttpServletRequest request, HttpServletResponse response, String message)
            throws ServletException, IOException {
        if (configured == null) {
            super.badRequest(request, response, message);
            return;
        }
        if ("POST".equals(request.getMethod())) {
            ImportFlow.clear(request, "productImport");
        }
        response.setStatus(400);
        request.setAttribute("errors", Map.of("file", message));
        render(request, response);
    }

    // Hiển thị trang danh mục sản phẩm từ Excel cùng dữ liệu đã chuẩn bị.
    private void render(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        request.setAttribute("title", "Nhập sản phẩm Excel");
        if (request.getAttribute("headers") == null) {
            var headers = new ArrayList<>(ProductImportService.headers(access(request)));
            if (!access(request).allows("COST_READ")) {
                headers.remove("Giá vốn");
            }
            request.setAttribute("headers", headers);
        }
        if (request.getAttribute("importStep") == null) {
            request.setAttribute("importStep", 1);
        }
        view(request, response, "imports/import");
    }
}
