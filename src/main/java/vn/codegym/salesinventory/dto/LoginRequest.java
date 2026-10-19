package vn.codegym.salesinventory.dto;

/**
 * S1-01: Mang tài khoản hoặc email và mật khẩu gửi từ biểu mẫu đăng nhập.
 */
public record LoginRequest(String identity, String password) {}
