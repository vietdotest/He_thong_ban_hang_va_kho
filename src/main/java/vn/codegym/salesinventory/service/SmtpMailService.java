package vn.codegym.salesinventory.service;

import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import java.util.Properties;
import vn.codegym.salesinventory.config.AppConfig;

public final class SmtpMailService implements MailService {
    private final AppConfig.MailSettings settings;

    public SmtpMailService(String host, int port, String from) {
        this(new AppConfig.MailSettings(host, port, from, "", "", false, false, false));
    }

    public SmtpMailService(AppConfig.MailSettings settings) {
        this.settings = java.util.Objects.requireNonNull(settings);
    }

    @Override
    public void sendPasswordReset(String recipient, String fullName, String resetUrl, int expiryMinutes) {
        send(recipient, "Đặt lại mật khẩu", """
                Xin chào %s,

                Chúng tôi nhận được yêu cầu đặt lại mật khẩu cho tài khoản của bạn.
                Mở liên kết sau để tạo mật khẩu mới:

                %s

                Liên kết có hiệu lực trong %d phút và chỉ sử dụng được một lần.
                Nếu bạn không gửi yêu cầu này, hãy bỏ qua email.
                """.formatted(fullName, resetUrl, expiryMinutes));
    }

    @Override
    public void sendTemporaryPassword(String recipient, String fullName, String username, String temporaryPassword) {
        send(recipient, "Tài khoản bán hàng và kho đã được tạo", """
                Xin chào %s,

                Tài khoản của bạn trên hệ thống bán hàng và kho đã được tạo.

                Tên đăng nhập: %s
                Mật khẩu tạm thời: %s

                Vui lòng đăng nhập và đổi mật khẩu ngay trong lần sử dụng đầu tiên.
                Không chuyển tiếp email này cho người khác.
                """.formatted(fullName, username, temporaryPassword));
    }

    @Override public void sendActivation(String email,String name,String username,String password,String url) {
        send(email,"Kích hoạt tài khoản bán hàng và kho","Xin chào " + name + "\nTên đăng nhập: " + username + "\nMật khẩu tạm: " + password + "\nLiên kết kích hoạt (24 giờ, một lần): " + url + "\nHãy đổi mật khẩu ở lần đăng nhập đầu tiên.");
    }
    Properties transportProperties() {
        Properties properties = new Properties();
        properties.setProperty("mail.smtp.host", settings.host());
        properties.setProperty("mail.smtp.port", Integer.toString(settings.port()));
        properties.setProperty("mail.smtp.auth", Boolean.toString(settings.authEnabled()));
        properties.setProperty("mail.smtp.starttls.enable", Boolean.toString(settings.starttlsEnabled()));
        properties.setProperty("mail.smtp.starttls.required", Boolean.toString(settings.starttlsRequired()));
        properties.setProperty("mail.smtp.ssl.checkserveridentity", "true");
        properties.setProperty("mail.smtp.ssl.protocols", "TLSv1.3 TLSv1.2");
        properties.setProperty("mail.smtp.connectiontimeout", "5000");
        properties.setProperty("mail.smtp.timeout", "5000");
        properties.setProperty("mail.smtp.writetimeout", "5000");
        return properties;
    }

    private void send(String recipient, String subject, String body) {
        Session mailSession = Session.getInstance(transportProperties());
        try {
            MimeMessage message = new MimeMessage(mailSession);
            message.setFrom(new InternetAddress(settings.from(), true));
            message.setRecipient(Message.RecipientType.TO, new InternetAddress(recipient, true));
            message.setSubject(subject, "UTF-8");
            message.setText(body, "UTF-8");
            if (settings.authEnabled()) Transport.send(message, settings.username(), settings.password());
            else Transport.send(message);
        } catch (MessagingException exception) {
            throw new IllegalStateException("Không thể gửi email qua SMTP.", exception);
        }
    }
}
