package vn.codegym.salesinventory.controller;

import jakarta.servlet.http.*;
import vn.codegym.salesinventory.dao.Sql;
import vn.codegym.salesinventory.service.ProfileService;
import vn.codegym.salesinventory.service.ImageStorage;
import vn.codegym.salesinventory.security.CurrentUser;
import vn.codegym.salesinventory.security.SessionKeys;
import jakarta.servlet.ServletException;
import java.io.IOException;
import java.util.Map;
import vn.codegym.salesinventory.validation.ProfileValidationException;

/**
 * S2-02: Hiển thị và tiếp nhận cập nhật hồ sơ cá nhân.
 */
public final class ProfileServlet extends PortalServlet {

    private final ProfileService configuredService;

    public ProfileServlet() {
        configuredService = null;
    }

    ProfileServlet(ProfileService service) {
        configuredService = service;
    }

    private ProfileService service() {
        return configuredService == null ? new ProfileService(source()) : configuredService;
    }

    // Chuẩn bị dữ liệu cần thiết trước khi xử lý biểu mẫu.
    protected void preparePost(HttpServletRequest request) throws Exception {
        if (!multipart(request)) {
            return;
        }
        try {
            request.getPart("image");
        } catch (IllegalStateException tooLarge) {
            throw new IllegalArgumentException(
                    "Ảnh JPG/PNG phải có dung lượng tối đa 2MB.", tooLarge);
        }
    }

    private boolean multipart(HttpServletRequest request) {
        return request.getContentType() != null
                && request.getContentType()
                        .toLowerCase(java.util.Locale.ROOT)
                        .startsWith("multipart/form-data");
    }

    // Nạp dữ liệu và hiển thị trang hồ sơ cá nhân.
    protected void get(HttpServletRequest request, HttpServletResponse response) throws Exception {
        var profile = service().find(actor(request).id());
        request.setAttribute("profile", profile);
        request.setAttribute(
                "form",
                Map.of(
                        "fullName",
                        Sql.text(profile.get("full_name")),
                        "phone",
                        Sql.text(profile.get("phone"))));
        view(request, response, "account/profile");
    }

    // Tiếp nhận và xử lý thay đổi hồ sơ cá nhân.
    protected void post(HttpServletRequest request, HttpServletResponse response) throws Exception {
        String name = request.getParameter("fullName"), phone = request.getParameter("phone");
        try {
            Part image = multipart(request) ? request.getPart("image") : null;
            if (image != null && image.getSize() > 0) {
                if (image.getSize() > ImageStorage.MAX_BYTES) {
                    throw new IllegalArgumentException(
                            "Ảnh JPG/PNG phải có dung lượng tối đa 2MB.");
                }
                byte[] bytes;
                try (var input = image.getInputStream()) {
                    bytes = input.readNBytes(ImageStorage.MAX_BYTES + 1);
                }
                service().updateWithAvatar(actor(request).id(), name, phone, bytes);
            } else {
                service().update(actor(request).id(), name, phone);
            }
            CurrentUser current = actor(request);
            request.getSession(false)
                    .setAttribute(
                            SessionKeys.CURRENT_USER,
                            new CurrentUser(
                                    current.id(),
                                    current.username(),
                                    current.email(),
                                    name.trim(),
                                    current.roleCodes(),
                                    current.mustChangePassword()));
            redirect(request, response, "/account/profile?notice=saved");
        } catch (ProfileValidationException exception) {
            invalid(request, response, exception.errors());
        } catch (IllegalArgumentException exception) {
            invalid(request, response, Map.of("image", exception.getMessage()));
        }
    }

    protected void badRequest(
            HttpServletRequest request, HttpServletResponse response, String message)
            throws ServletException, IOException {
        invalid(request, response, Map.of("image", message));
    }

    // Hiển thị lại hồ sơ với dữ liệu đã nhập và lỗi tương ứng.
    private void invalid(
            HttpServletRequest request, HttpServletResponse response, Map<String, String> errors)
            throws ServletException, IOException {
        response.setStatus(400);
        request.setAttribute("profile", service().find(actor(request).id()));
        request.setAttribute("form", formValues(request, "fullName", "phone"));
        request.setAttribute("errors", errors);
        view(request, response, "account/profile");
    }
}
