package vn.codegym.salesinventory.service;

/**
 * S1-03, S1-08: Quy định các thao tác gửi email đặt lại mật khẩu và kích hoạt tài khoản.
 */
public interface MailService {

    default void sendActivation(
            String email, String name, String username, String password, String url) {
        throw new UnsupportedOperationException("Chưa cấu hình email kích hoạt.");
    }

    // Gửi email chứa liên kết đặt lại mật khẩu.
    void sendPasswordReset(String recipient, String fullName, String resetUrl, int expiryMinutes);

    default void sendTemporaryPassword(
            String recipient, String fullName, String username, String temporaryPassword) {
        throw new UnsupportedOperationException("Temporary-password delivery is not configured");
    }
}
