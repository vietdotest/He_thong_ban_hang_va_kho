package vn.codegym.salesinventory.service;

public interface MailService {
    void sendPasswordReset(String recipient, String fullName, String resetUrl, int expiryMinutes);

    default void sendTemporaryPassword(String recipient, String fullName, String username, String temporaryPassword) {
        throw new UnsupportedOperationException("Temporary-password delivery is not configured");
    }
}
