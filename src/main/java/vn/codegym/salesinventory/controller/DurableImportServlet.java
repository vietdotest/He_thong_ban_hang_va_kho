package vn.codegym.salesinventory.controller;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import vn.codegym.salesinventory.config.ApplicationContextKeys;
import vn.codegym.salesinventory.dto.PageRequest;
import vn.codegym.salesinventory.service.*;

/**
 * S2-01, S2-08: Dùng chung luồng tải Excel, xem trước, xác nhận và theo dõi công việc nhập.
 */
public abstract class DurableImportServlet extends PortalServlet {

    protected abstract String importKind();

    protected abstract ImportPreview readPreview(long actor, byte[] bytes);

    protected ImportJobService jobs() {
        return (ImportJobService)
                getServletContext().getAttribute(ApplicationContextKeys.IMPORT_JOB_SERVICE);
    }

    private String path() {
        return importKind().equals("USER") ? "/admin/users/import" : "/catalog/products/import";
    }

    private String key() {
        return importKind().equals("USER") ? "userImportJobId" : "productImportJobId";
    }

    private String permission() {
        return importKind().equals("USER") ? "USER_MANAGE" : "PRODUCT_MANAGE";
    }

    // Lấy mã công việc nhập từ yêu cầu hoặc phiên hiện tại.
    private String jobId(HttpServletRequest request) {
        String id = value(request, "job");
        if (id.isEmpty()) {
            Object saved = request.getSession().getAttribute(key());
            id = saved instanceof String ? (String) saved : "";
        }
        if (!id.isEmpty() && !id.matches("[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}")) {
            throw new IllegalArgumentException("Mã tác vụ không hợp lệ.");
        }
        return id;
    }

    // Nạp dữ liệu và hiển thị trang công việc nhập Excel.
    @Override
    protected void get(HttpServletRequest request, HttpServletResponse response) throws Exception {
        access(request).require(permission());
        if (value(request, "new").equals("1")) {
            request.getSession().removeAttribute(key());
        }
        if (value(request, "template").equals("1")) {
            List<String> headers;
            List<String> sample;
            if (importKind().equals("USER")) {
                headers = UserImportService.HEADERS;
                sample =
                        List.of(
                                "nhanvien01",
                                "nhanvien@example.com",
                                "Nguyễn Văn An",
                                "0901234567",
                                "SALES",
                                "",
                                "");
            } else {
                headers = ProductImportService.headers(access(request));
                sample =
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
            }
            download(
                    response,
                    importKind().equals("USER") ? "nguoi-dung.xlsx" : "san-pham.xlsx",
                    Xlsx.write(List.of(headers, sample)));
            return;
        }
        String id = jobId(request);
        if (value(request, "report").equals("1")) {
            if (id.isEmpty()) {
                throw new IllegalArgumentException("Hãy chọn tác vụ trước.");
            }
            download(
                    response,
                    "bao-cao-nhap.xlsx",
                    jobs().report(actor(request).id(), importKind(), id));
            return;
        }
        if (value(request, "progress").equals("1")) {
            if (id.isEmpty()) {
                throw new IllegalArgumentException("Hãy chọn tác vụ trước.");
            }
            var dto = new LinkedHashMap<>(jobs().progress(actor(request).id(), importKind(), id));
            var rows =
                    jobs().rows(
                                    actor(request).id(),
                                    importKind(),
                                    id,
                                    value(request, "q"),
                                    value(request, "state"),
                                    PageRequest.parse(
                                            value(request, "page"), value(request, "pageSize")));
            dto.put("rows", rows.items());
            dto.put("page", rows.page());
            dto.put("pageSize", rows.pageSize());
            dto.put("totalItems", rows.totalItems());
            dto.put("totalPages", rows.getTotalPages());
            response.setContentType("application/json;charset=UTF-8");
            response.setHeader("Cache-Control", "no-store");
            new ObjectMapper().writeValue(response.getWriter(), dto);
            return;
        }
        show(request, response, id);
    }

    // Trả tệp kết quả cho người dùng có quyền xem.
    private static void download(HttpServletResponse response, String name, byte[] bytes)
            throws IOException {
        response.setContentType(
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setHeader("Content-Disposition", "attachment; filename=" + name);
        response.setHeader("Cache-Control", "no-store");
        response.getOutputStream().write(bytes);
    }

    protected final Part uploadFile(HttpServletRequest request) throws IOException {
        return MultipartUploads.read(
                request, "file", "Vui lòng chọn tệp Excel (.xlsx).", "Tệp XLSX tối đa 10MB.");
    }

    // Chuẩn bị dữ liệu cần thiết trước khi xử lý biểu mẫu.
    @Override
    protected void preparePost(HttpServletRequest request) throws Exception {
        access(request).require(permission());
        if (MultipartUploads.multipart(request)) {
            uploadFile(request);
        }
    }

    // Tiếp nhận và xử lý thay đổi công việc nhập Excel.
    @Override
    protected void post(HttpServletRequest request, HttpServletResponse response) throws Exception {
        access(request).require(permission());
        String id = jobId(request);
        boolean confirming = value(request, "action").equals("confirm");
        try {
            if (confirming) {
                if (id.isEmpty()) {
                    throw new IllegalArgumentException("Hãy tải lên tệp trước.");
                }
                jobs().confirm(
                                actor(request).id(),
                                importKind(),
                                id,
                                number(request, "version"),
                                value(request, "token"));
            } else {
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
                id =
                        jobs().create(
                                        actor(request).id(),
                                        importKind(),
                                        file.getSubmittedFileName(),
                                        readPreview(actor(request).id(), bytes));
            }
            request.getSession().setAttribute(key(), id);
            // Clear any legacy in-memory state when upgrading an existing session.
            String old = importKind().equals("USER") ? "userImport" : "productImport";
            for (String suffix : List.of("", "Report", "File")) {
                request.getSession().removeAttribute(old + suffix);
            }
            StringBuilder url = new StringBuilder(path()).append("?job=").append(id);
            if (confirming) {
                for (String filter : List.of("q", "state", "page", "pageSize")) {
                    if (!value(request, filter).isEmpty()) {
                        url.append('&')
                                .append(filter)
                                .append('=')
                                .append(
                                        URLEncoder.encode(
                                                value(request, filter), StandardCharsets.UTF_8));
                    }
                }
            }
            redirect(request, response, url.toString());
        } catch (IllegalArgumentException invalid) {
            response.setStatus(400);
            request.setAttribute(
                    "errors", Map.of(confirming ? "preview" : "file", invalid.getMessage()));
            show(request, response, confirming ? id : "");
        }
    }

    // Đưa kết quả nhập được phép xem lên màn hình.
    private void show(HttpServletRequest request, HttpServletResponse response, String id)
            throws ServletException, IOException {
        request.setAttribute("userImportPage", importKind().equals("USER"));
        request.setAttribute(
                "title",
                importKind().equals("USER") ? "Nhập người dùng Excel" : "Nhập sản phẩm Excel");
        request.setAttribute("importPath", path());
        request.setAttribute("importStep", 1);
        if (!id.isEmpty()) {
            var job = jobs().find(actor(request).id(), importKind(), id);
            var rows =
                    jobs().rows(
                                    actor(request).id(),
                                    importKind(),
                                    id,
                                    value(request, "q"),
                                    value(request, "state"),
                                    PageRequest.parse(
                                            value(request, "page"), value(request, "pageSize")));
            request.setAttribute("job", job);
            request.setAttribute("pagination", rows);
            request.setAttribute("rows", rows.items());
            request.setAttribute("headers", job.get("headers"));
            request.setAttribute("importStep", job.get("status").equals("PREVIEW") ? 2 : 3);
        }
        view(request, response, "imports/job");
    }

    // Hiển thị lại dữ liệu và thông báo khi yêu cầu không hợp lệ.
    @Override
    protected void badRequest(
            HttpServletRequest request, HttpServletResponse response, String message)
            throws ServletException, IOException {
        response.setStatus(400);
        request.setAttribute("errors", Map.of("file", message));
        show(request, response, "");
    }
}
