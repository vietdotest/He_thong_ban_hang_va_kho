package vn.codegym.salesinventory.controller;

import jakarta.servlet.http.*;
import jakarta.servlet.ServletException;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import vn.codegym.salesinventory.service.*;
import vn.codegym.salesinventory.config.ApplicationContextKeys;

public final class UserImportServlet extends PortalServlet {
    private final UserImportService injectedService;

    public UserImportServlet() { this.injectedService = null; }
    UserImportServlet(UserImportService service) { this.injectedService = service; }

    private UserImportService service() {
        return injectedService != null ? injectedService : new UserImportService(source(),
                (UserManagementService) getServletContext().getAttribute(ApplicationContextKeys.USER_MANAGEMENT_SERVICE));
    }

    @Override protected void preparePost(HttpServletRequest request) throws Exception {
        String type = request.getContentType();
        if (type == null || !type.toLowerCase(java.util.Locale.ROOT).startsWith("multipart/form-data")) return;
        try { request.getPart("file"); }
        catch (Exception e) {
            for (Throwable cause = e; cause != null; cause = cause.getCause()) {
                String name = cause.getClass().getName();
                if (name.contains(".fileupload.") && (name.endsWith("SizeLimitExceededException") || name.endsWith("FileSizeLimitExceededException")))
                    throw new IllegalArgumentException("Tệp XLSX tối đa 10MB.", e);
            }
            throw e;
        }
    }

    @Override protected void get(HttpServletRequest request, HttpServletResponse response) throws Exception {
        if (value(request, "template").equals("1")) {
            response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
            response.setHeader("Content-Disposition", "attachment; filename=nguoi-dung.xlsx");
            response.getOutputStream().write(Xlsx.write(List.of(UserImportService.HEADERS,
                    List.of("nhanvien01", "nhanvien@example.com", "Nguyễn Văn An", "0901234567", "SALES", "", ""))));
            return;
        }
        render(request, response);
    }

    @Override protected void post(HttpServletRequest request, HttpServletResponse response) throws Exception {
        boolean confirm = value(request, "action").equals("confirm");
        try {
            if (confirm) {
                var preview = (ImportPreview) request.getSession().getAttribute("userImport");
                if (preview == null) throw new IllegalArgumentException("Hãy tải lên tệp trước.");
                try {
                    var result = service().confirm(actor(request).id(), preview, value(request, "token"),
                            RequestMetadata.authenticationContext(request));
                    request.setAttribute("report", result);
                    request.setAttribute("successCount", result.stream().filter(ImportPreview.Line::isValid).count());
                    request.setAttribute("failureCount", result.stream().filter(line -> !line.isValid()).count());
                } finally { request.getSession().removeAttribute("userImport"); }
            } else {
                request.getSession().removeAttribute("userImport");
                var file = request.getPart("file");
                if (file == null || file.getSize() == 0) throw new IllegalArgumentException("Vui lòng chọn tệp XLSX.");
                if (file.getSize() > Xlsx.MAX_BYTES) throw new IllegalArgumentException("Tệp XLSX tối đa 10MB.");
                byte[] bytes;
                try (var input = file.getInputStream()) { bytes = input.readNBytes(Xlsx.MAX_BYTES + 1); }
                var preview = service().preview(actor(request).id(), bytes);
                request.getSession().setAttribute("userImport", preview);
                request.setAttribute("preview", preview);
                request.setAttribute("validCount", preview.getLines().stream().filter(ImportPreview.Line::isValid).count());
                request.setAttribute("invalidCount", preview.getLines().stream().filter(line -> !line.isValid()).count());
            }
        } catch (IllegalArgumentException e) {
            response.setStatus(400);
            request.setAttribute("errors", Map.of(confirm ? "preview" : "file", e.getMessage()));
        }
        render(request, response);
    }

    @Override protected void badRequest(HttpServletRequest request, HttpServletResponse response, String message) throws ServletException, IOException {
        if (request.getSession(false) != null) request.getSession(false).removeAttribute("userImport");
        response.setStatus(400);
        request.setAttribute("errors", Map.of("file", message));
        render(request, response);
    }

    private void render(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        request.setAttribute("userImportPage", true);
        request.setAttribute("title", "Nhập người dùng Excel");
        request.setAttribute("headers", UserImportService.HEADERS);
        view(request, response, "imports/import");
    }
}
