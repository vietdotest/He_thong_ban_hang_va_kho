package vn.codegym.salesinventory.service;

public interface MailService {
    default void sendActivation(String email,String name,String username,String password,String url) { throw new UnsupportedOperationException("Chưa cấu hình email kích hoạt."); }

    void sendPasswordReset(String recipient, String fullName, String resetUrl, int expiryMinutes);

    default void sendTemporaryPassword(String recipient, String fullName, String username, String temporaryPassword) {
        throw new UnsupportedOperationException("Temporary-password delivery is not configured");
    }
}
