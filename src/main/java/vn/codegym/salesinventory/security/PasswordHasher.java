package vn.codegym.salesinventory.security;

/**
 * S1-01, S1-03, S1-04: Quy định cách băm và kiểm tra mật khẩu để service không phụ thuộc thuật toán cụ thể.
 */
public interface PasswordHasher {

    // Đối chiếu mật khẩu nhập vào với giá trị đã băm.
    boolean matches(String rawPassword, String encodedPassword);

    // Băm giá trị đầu vào theo thuật toán đang dùng.
    String hash(String rawPassword);
}
