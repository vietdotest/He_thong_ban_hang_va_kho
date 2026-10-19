package vn.codegym.salesinventory.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import vn.codegym.salesinventory.security.Access;
import vn.codegym.salesinventory.service.ImportPreview;
import vn.codegym.salesinventory.service.ImportReport;

/**
 * S2-01, S2-08: Chuẩn hóa tên tệp và quản lý dữ liệu tạm của luồng nhập Excel trong phiên.
 */
final class ImportFlow {

    private ImportFlow() {}

    // Loại đường dẫn và ký tự điều khiển khỏi tên tệp tải lên.
    static String filename(String supplied) {
        if (supplied == null || supplied.isBlank()) {
            return "du-lieu.xlsx";
        }
        String name = supplied.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1);
        name = name.replaceAll("[\\p{Cntrl}]", "");
        return name.substring(0, Math.min(180, name.length()));
    }

    // Xóa dữ liệu tạm của lượt nhập khỏi phiên hiện tại.
    static void clear(HttpServletRequest request, String key) {
        var session = request.getSession(false);
        if (session != null) {
            session.removeAttribute(key);
            session.removeAttribute(key + "Report");
            session.removeAttribute(key + "File");
        }
    }

    // Kiểm tra dữ liệu và tạo bản xem trước để người dùng xác nhận.
    static void preview(
            HttpServletRequest request, String key, ImportPreview preview, String filename) {
        request.getSession().setAttribute(key, preview);
        request.getSession().setAttribute(key + "File", filename);
        request.setAttribute("preview", preview);
        request.setAttribute("filename", filename);
        request.setAttribute("rowCount", preview.getLines().size());
        summary(request, preview.getLines(), false);
        request.setAttribute("importStep", 2);
    }

    // Lưu và hiển thị kết quả của lượt nhập dữ liệu.
    static void result(
            HttpServletRequest request,
            String key,
            long actor,
            boolean users,
            List<String> headers,
            List<ImportPreview.Line> lines,
            Access access) {
        Object name = request.getSession().getAttribute(key + "File");
        var report =
                new ImportReport(
                        actor,
                        users,
                        filename(name == null ? null : name.toString()),
                        headers,
                        lines);
        request.getSession().setAttribute(key + "Report", report);
        show(request, report, actor, access);
    }

    // Đưa kết quả nhập được phép xem lên màn hình.
    static void show(HttpServletRequest request, ImportReport report, long actor, Access access) {
        var rows = report.lines(actor, access);
        request.setAttribute("report", rows);
        request.setAttribute("headers", report.headers(actor, access));
        request.setAttribute("filename", report.getFilename());
        request.setAttribute("rowCount", rows.size());
        request.setAttribute("importStep", 3);
        summary(request, rows, true);
    }

    // Đếm dòng hợp lệ và dòng lỗi để hiển thị tiến độ nhập.
    static void summary(HttpServletRequest request, List<ImportPreview.Line> rows, boolean done) {
        long valid = rows.stream().filter(ImportPreview.Line::isValid).count(),
                invalid = rows.size() - valid;
        request.setAttribute(done ? "successCount" : "validCount", valid);
        request.setAttribute(done ? "failureCount" : "invalidCount", invalid);
        request.setAttribute(
                "createdCount",
                rows.stream()
                        .filter(ImportPreview.Line::isValid)
                        .filter(l -> l.operation().equals("Tạo mới"))
                        .count());
        request.setAttribute(
                "updatedCount",
                rows.stream()
                        .filter(ImportPreview.Line::isValid)
                        .filter(l -> l.operation().equals("Cập nhật"))
                        .count());
        request.setAttribute("skippedCount", invalid);
        var names =
                java.util.Map.of(
                        "ADMIN",
                        "Quản trị hệ thống",
                        "SALES_MANAGER",
                        "Quản lý kinh doanh",
                        "SALES",
                        "Nhân viên kinh doanh",
                        "WAREHOUSE_MANAGER",
                        "Trưởng kho",
                        "WAREHOUSE",
                        "Nhân viên kho",
                        "ACCOUNTANT",
                        "Kế toán",
                        "DIRECTOR",
                        "Ban giám đốc");
        var roles = new java.util.HashMap<Integer, String>();
        for (var line : rows) {
            if (line.cells().size() > 4) {
                roles.put(
                        line.number(),
                        java.util.Arrays.stream(line.cells().get(4).split("[,;]"))
                                .map(String::trim)
                                .map(code -> names.getOrDefault(code, code))
                                .collect(java.util.stream.Collectors.joining(", ")));
            }
        }
        request.setAttribute("importRoleLabels", roles);
    }

    // Trả tệp kết quả cho người dùng có quyền xem.
    static void download(
            HttpServletRequest request,
            HttpServletResponse response,
            String key,
            long actor,
            Access access)
            throws IOException {
        Object stored = request.getSession(false).getAttribute(key + "Report");
        if (!(stored instanceof ImportReport report)) {
            throw new IllegalArgumentException(
                    "Báo cáo không còn khả dụng. Hãy thực hiện lượt nhập mới.");
        }
        byte[] bytes = report.workbook(actor, access);
        response.setContentType(
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setHeader(
                "Content-Disposition",
                "attachment; filename="
                        + (key.equals("userImport")
                                ? "bao-cao-nguoi-dung.xlsx"
                                : "bao-cao-san-pham.xlsx"));
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.getOutputStream().write(bytes);
    }
}
