package vn.codegym.salesinventory.service;

public interface MailService {
    void sendPasswordReset(String recipient, String fullName, String resetUrl, int expiryMinutes);
}
