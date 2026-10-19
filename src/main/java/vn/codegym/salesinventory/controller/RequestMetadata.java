package vn.codegym.salesinventory.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import vn.codegym.salesinventory.dto.AuthenticationContext;

/**
 * S1-01, dùng chung: Lấy IP, thông tin trình duyệt và hỗ trợ chuyển hướng sau xử lý yêu cầu.
 */
final class RequestMetadata {

    private static final int MAX_USER_AGENT_LENGTH = 512;

    private RequestMetadata() {}

    // Lấy IP và thông tin trình duyệt để ghi nhận thao tác xác thực.
    static AuthenticationContext authenticationContext(HttpServletRequest request) {
        String userAgent = request.getHeader("User-Agent");
        if (userAgent != null && userAgent.length() > MAX_USER_AGENT_LENGTH) {
            userAgent = userAgent.substring(0, MAX_USER_AGENT_LENGTH);
        }
        return new AuthenticationContext(request.getRemoteAddr(), userAgent);
    }

    static void seeOther(HttpServletResponse response, String location) {
        response.setStatus(HttpServletResponse.SC_SEE_OTHER);
        response.setHeader("Location", response.encodeRedirectURL(location));
    }
}
