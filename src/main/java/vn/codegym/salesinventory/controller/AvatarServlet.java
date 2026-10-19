package vn.codegym.salesinventory.controller;

import jakarta.servlet.http.*;
import java.nio.file.*;
import vn.codegym.salesinventory.dao.Sql;
import vn.codegym.salesinventory.service.ImageStorage;
import vn.codegym.salesinventory.service.AvatarService;
import vn.codegym.salesinventory.service.ProfileService;
import java.util.Map;

/**
 * S2-03: Nhận ảnh tải lên và phục vụ ảnh đại diện của người dùng.
 */
public final class AvatarServlet extends PortalServlet {

    private final AvatarService configured;

    private final ProfileService profile;

    public AvatarServlet() {
        configured = null;
        profile = null;
    }

    AvatarServlet(AvatarService configured, ProfileService profile) {
        this.configured = configured;
        this.profile = profile;
    }

    // Trả ảnh đại diện theo tài khoản và kích thước được yêu cầu.
    protected void get(HttpServletRequest request, HttpServletResponse response) throws Exception {
        long id = value(request, "id").isBlank() ? actor(request).id() : number(request, "id");
        String key =
                Sql.transaction(
                        source(),
                        connection ->
                                Sql.text(
                                        Sql.one(
                                                        connection,
                                                        "SELECT avatar_key FROM users WHERE id=?",
                                                        id)
                                                .get("avatar_key")));
        if (key.isBlank()) {
            response.sendError(404);
            return;
        }
        Path path = ImageStorage.configured().path(key, !value(request, "size").equals("full"));
        if (!Files.exists(path)) {
            response.sendError(404);
            return;
        }
        response.setContentType("image/png");
        response.setHeader("X-Content-Type-Options", "nosniff");
        Files.copy(path, response.getOutputStream());
    }

    private jakarta.servlet.http.Part image(HttpServletRequest request) throws java.io.IOException {
        return MultipartUploads.read(
                request, "image", "Vui lòng chọn ảnh JPG/PNG.", "Chọn ảnh JPG/PNG tối đa 2MB.");
    }

    // Chuẩn bị dữ liệu cần thiết trước khi xử lý biểu mẫu.
    @Override
    protected void preparePost(HttpServletRequest request) throws Exception {
        access(request).require("PROFILE");
        if (MultipartUploads.multipart(request)) {
            image(request);
        }
    }

    @Override
    protected void badRequest(
            HttpServletRequest request, HttpServletResponse response, String message)
            throws jakarta.servlet.ServletException, java.io.IOException {
        invalid(request, response, message);
    }

    // Hiển thị lại trang ảnh đại diện cùng thông báo dữ liệu không hợp lệ.
    private void invalid(HttpServletRequest request, HttpServletResponse response, String message)
            throws jakarta.servlet.ServletException, java.io.IOException {
        var value =
                (profile == null ? new ProfileService(source()) : profile)
                        .find(actor(request).id());
        request.setAttribute("profile", value);
        request.setAttribute(
                "form",
                Map.of(
                        "fullName",
                        Sql.text(value.get("full_name")),
                        "phone",
                        Sql.text(value.get("phone"))));
        request.setAttribute("errors", Map.of("image", message));
        response.setStatus(400);
        view(request, response, "account/profile");
    }

    // Tiếp nhận và xử lý thay đổi ảnh đại diện.
    protected void post(HttpServletRequest request, HttpServletResponse response) throws Exception {
        access(request).require("PROFILE");
        try {
            var part = image(request);
            if (part == null || part.getSize() == 0) {
                throw new IllegalArgumentException("Vui lòng chọn ảnh JPG/PNG.");
            }
            if (part.getSize() > ImageStorage.MAX_BYTES) {
                throw new IllegalArgumentException("Chọn ảnh JPG/PNG tối đa 2MB.");
            }
            byte[] bytes;
            try (var input = part.getInputStream()) {
                bytes = input.readNBytes(ImageStorage.MAX_BYTES + 1);
            }
            (configured == null ? new AvatarService(source()) : configured)
                    .replace(actor(request).id(), bytes);
            redirect(request, response, "/account/profile?notice=saved");
        } catch (IllegalArgumentException invalid) {
            invalid(request, response, invalid.getMessage());
        }
    }
}
