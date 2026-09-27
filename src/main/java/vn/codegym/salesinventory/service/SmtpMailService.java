package vn.codegym.salesinventory.service;

import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import java.util.Properties;

public final class SmtpMailService implements MailService {
    private final String host;
    private final int port;
    private final String from;

    public SmtpMailService(String host, int port, String from) {
        this.host = host;
        this.port = port;
        this.from = from;
    }

    @Override
    public void sendPasswordReset(String recipient, String fullName, String resetUrl, int expiryMinutes) {
        Properties properties = new Properties();
        properties.setProperty("mail.smtp.host", host);
        properties.setProperty("mail.smtp.port", Integer.toString(port));
        properties.setProperty("mail.smtp.connectiontimeout", "5000");
        properties.setProperty("mail.smtp.timeout", "5000");
        Session mailSession = Session.getInstance(properties);
        try {
            MimeMessage message = new MimeMessage(mailSession);
            message.setFrom(new InternetAddress(from));
            message.setRecipient(Message.RecipientType.TO, new InternetAddress(recipient));
            message.setSubject("Đặt lại mật khẩu", "UTF-8");
            message.setText("""
                    Xin chào %s,

                    Chúng tôi nhận được yêu cầu đặt lại mật khẩu cho tài khoản của bạn.
                    Mở liên kết sau để tạo mật khẩu mới:

                    %s

                    Liên kết có hiệu lực trong %d phút và chỉ sử dụng được một lần.
                    Nếu bạn không gửi yêu cầu này, hãy bỏ qua email.
                    """.formatted(fullName, resetUrl, expiryMinutes), "UTF-8");
            Transport.send(message);
        } catch (MessagingException exception) {
            throw new IllegalStateException("Password reset email could not be sent", exception);
        }
    }
}
