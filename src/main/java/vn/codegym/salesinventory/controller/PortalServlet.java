package vn.codegym.salesinventory.controller;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.*;
import javax.sql.DataSource;
import vn.codegym.salesinventory.config.ApplicationContextKeys;
import vn.codegym.salesinventory.security.*;

/**
 * Dùng chung: Lớp cha của các màn hình: đọc tham số, kiểm CSRF, điều hướng và xử lý lỗi.
 */
public abstract class PortalServlet extends HttpServlet {

    protected DataSource source() {
        return (DataSource) getServletContext().getAttribute(ApplicationContextKeys.DATA_SOURCE);
    }

    protected CurrentUser actor(HttpServletRequest request) {
        return (CurrentUser) request.getSession(false).getAttribute(SessionKeys.CURRENT_USER);
    }

    protected Access access(HttpServletRequest request) {
        return (Access) request.getAttribute("access");
    }

    // Đọc tham số, bỏ khoảng trắng đầu cuối và dùng chuỗi rỗng khi thiếu.
    protected static String value(HttpServletRequest request, String key) {
        String value = request.getParameter(key);
        return value == null ? "" : value.trim();
    }

    // Giữ dữ liệu đã nhập để hiển thị lại biểu mẫu khi có lỗi.
    protected static Map<String, Object> formValues(HttpServletRequest request, String... fields) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (String field : fields) {
            String value = request.getParameter(field);
            values.put(field, value == null ? "" : value);
        }
        return values;
    }

    protected static Map<String, String> fieldErrors(IllegalArgumentException error) {
        return error instanceof vn.codegym.salesinventory.validation.FieldValidationException fields
                ? fields.errors()
                : Map.of("form", error.getMessage());
    }

    // Đọc mã bản ghi từ yêu cầu và báo lỗi nếu không phải số.
    protected static long number(HttpServletRequest request, String key) {
        try {
            return Long.parseLong(value(request, key));
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Mã bản ghi không hợp lệ.");
        }
    }

    // Chuyển yêu cầu đến trang JSP cùng dữ liệu đã chuẩn bị.
    protected void view(HttpServletRequest request, HttpServletResponse response, String path)
            throws ServletException, IOException {
        String uri = request.getRequestURI();
        if (uri != null) {
            request.setAttribute(
                    "paginationPath",
                    uri.substring(
                            Objects.requireNonNullElse(request.getContextPath(), "").length()));
        }
        request.getRequestDispatcher("/WEB-INF/views/" + path + ".jsp").forward(request, response);
    }

    // Chuyển trình duyệt sang địa chỉ sau khi xử lý biểu mẫu.
    protected void redirect(HttpServletRequest request, HttpServletResponse response, String path) {
        response.setStatus(303);
        response.setHeader("Location", request.getContextPath() + path);
    }

    // Trả lỗi phương thức không được hỗ trợ nếu màn hình chưa định nghĩa xử lý GET.
    protected void get(HttpServletRequest request, HttpServletResponse response) throws Exception {
        response.sendError(405);
    }

    // Trả lỗi phương thức không được hỗ trợ nếu màn hình chưa định nghĩa xử lý POST.
    protected void post(HttpServletRequest request, HttpServletResponse response) throws Exception {
        response.sendError(405);
    }

    protected void preparePost(HttpServletRequest request) throws Exception {}

    // Hiển thị lại dữ liệu và thông báo khi yêu cầu không hợp lệ.
    protected void badRequest(
            HttpServletRequest request, HttpServletResponse response, String message)
            throws ServletException, IOException {
        response.setStatus(400);
        request.setAttribute("message", message);
        request.setAttribute("returnPath", request.getRequestURI());
        view(request, response, "message");
    }

    // Chuyển yêu cầu GET qua luồng xử lý và bắt lỗi dùng chung.
    @Override
    protected final void doGet(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        dispatch(request, response, false);
    }

    // Chuyển yêu cầu POST qua bước kiểm CSRF và xử lý biểu mẫu dùng chung.
    @Override
    protected final void doPost(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        dispatch(request, response, true);
    }

    // Kiểm CSRF, gọi xử lý GET hoặc POST và chuyển lỗi sang phản hồi HTTP phù hợp.
    private void dispatch(HttpServletRequest request, HttpServletResponse response, boolean post)
            throws ServletException, IOException {
        try {
            if (post) {
                preparePost(request);
            }
            if (post
                    && !((CsrfTokenManager)
                                    getServletContext()
                                            .getAttribute(
                                                    ApplicationContextKeys.CSRF_TOKEN_MANAGER))
                            .isValid(request.getSession(false), request.getParameter("_csrf"))) {
                response.sendError(403);
                return;
            }
            if (post) {
                post(request, response);
            } else {
                get(request, response);
            }
        } catch (SecurityException exception) {
            response.sendError(403);
        } catch (IllegalArgumentException e) {
            badRequest(request, response, e.getMessage());
        } catch (Exception e) {
            getServletContext().log("Không thể xử lý yêu cầu", e);
            response.sendError(500);
        }
    }
}
