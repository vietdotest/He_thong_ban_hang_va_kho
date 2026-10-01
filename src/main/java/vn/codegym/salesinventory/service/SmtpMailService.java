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
    private void send(String recipient, String subject, String body) {
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
            message.setSubject(subject, "UTF-8");
            message.setText(body, "UTF-8");
            Transport.send(message);
        } catch (MessagingException exception) {
            throw new IllegalStateException("Email could not be sent", exception);
        }
    }
}
