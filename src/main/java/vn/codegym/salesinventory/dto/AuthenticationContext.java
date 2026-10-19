package vn.codegym.salesinventory.dto;

/**
 * S1-01: Mang IP và thông tin trình duyệt của lần đăng nhập.
 */
public record AuthenticationContext(String ipAddress, String userAgent) {}
