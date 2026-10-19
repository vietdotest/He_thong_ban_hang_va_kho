package vn.codegym.salesinventory.service;

import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.eclipse.angus.mail.smtp.SMTPSendFailedException;
import org.eclipse.angus.mail.smtp.SMTPAddressFailedException;
import java.util.Properties;
import vn.codegym.salesinventory.config.AppConfig;

/**
 * S1-03, S1-08: Gửi email đặt lại mật khẩu và kích hoạt qua máy chủ SMTP đã cấu hình.
 */
public final class SmtpMailService implements MailService {

    private final AppConfig.MailSettings settings;

    // Tạo kết nối gửi thư để có thể thay thế khi kiểm thử.
    @FunctionalInterface
    interface TransportFactory {

        // Tạo email hệ thống từ dữ liệu đầu vào.
        Transport create(Session session) throws MessagingException;
    }

    private final TransportFactory transports;

    public SmtpMailService(String host, int port, String from) {
        this(new AppConfig.MailSettings(host, port, from, "", "", false, false, false));
    }

    public SmtpMailService(AppConfig.MailSettings settings) {
        this(settings, session -> session.getTransport("smtp"));
    }

    SmtpMailService(AppConfig.MailSettings settings, TransportFactory transports) {
        this.settings = java.util.Objects.requireNonNull(settings);
        this.transports = java.util.Objects.requireNonNull(transports);
    }

    // Gửi email chứa liên kết đặt lại mật khẩu.
    @Override
    public void sendPasswordReset(
            String recipient, String fullName, String resetUrl, int expiryMinutes) {
        send(
                recipient,
                "Đặt lại mật khẩu",
                """
                Xin chào %s,

                Chúng tôi nhận được yêu cầu đặt lại mật khẩu cho tài khoản của bạn.
                Mở liên kết sau để tạo mật khẩu mới:

                %s

                Liên kết có hiệu lực trong %d phút và chỉ sử dụng được một lần.
                Nếu bạn không gửi yêu cầu này, hãy bỏ qua email.
                """
                        .formatted(fullName, resetUrl, expiryMinutes));
    }

    // Gửi mật khẩu tạm đến địa chỉ email của tài khoản.
    @Override
    public void sendTemporaryPassword(
            String recipient, String fullName, String username, String temporaryPassword) {
        send(
                recipient,
                "Tài khoản bán hàng và kho đã được tạo",
                """
                Xin chào %s,

                Tài khoản của bạn trên hệ thống bán hàng và kho đã được tạo.

                Tên đăng nhập: %s
                Mật khẩu tạm thời: %s

                Vui lòng đăng nhập và đổi mật khẩu ngay trong lần sử dụng đầu tiên.
                Không chuyển tiếp email này cho người khác.
                """
                        .formatted(fullName, username, temporaryPassword));
    }

    // Gửi email chứa thông tin kích hoạt tài khoản.
    @Override
    public void sendActivation(
            String email, String name, String username, String password, String url) {
        send(
                email,
                "Kích hoạt tài khoản bán hàng và kho",
                "Xin chào "
                        + name
                        + "\nTên đăng nhập: "
                        + username
                        + "\nMật khẩu tạm: "
                        + password
                        + "\nLiên kết kích hoạt (24 giờ, một lần): "
                        + url
                        + "\nHãy đổi mật khẩu ở lần đăng nhập đầu tiên.");
    }

    // Tạo cấu hình kết nối SMTP từ thiết lập email.
    Properties transportProperties() {
        Properties properties = new Properties();
        properties.setProperty("mail.smtp.host", settings.host());
        properties.setProperty("mail.smtp.port", Integer.toString(settings.port()));
        properties.setProperty("mail.smtp.auth", Boolean.toString(settings.authEnabled()));
        properties.setProperty(
                "mail.smtp.starttls.enable", Boolean.toString(settings.starttlsEnabled()));
        properties.setProperty(
                "mail.smtp.starttls.required", Boolean.toString(settings.starttlsRequired()));
        properties.setProperty("mail.smtp.ssl.checkserveridentity", "true");
        properties.setProperty("mail.smtp.ssl.protocols", "TLSv1.3 TLSv1.2");
        properties.setProperty("mail.smtp.connectiontimeout", "5000");
        properties.setProperty("mail.smtp.timeout", "5000");
        properties.setProperty("mail.smtp.writetimeout", "5000");
        return properties;
    }

    // Gửi email bằng cấu hình hiện tại.
    private void send(String recipient, String subject, String body) {
        Session mailSession = Session.getInstance(transportProperties());
        Transport transport = null;
        boolean sending = false;
        try {
            MimeMessage message = new MimeMessage(mailSession);
            message.setFrom(new InternetAddress(settings.from(), true));
            message.setRecipient(Message.RecipientType.TO, new InternetAddress(recipient, true));
            message.setSubject(subject, "UTF-8");
            message.setText(body, "UTF-8");
            message.saveChanges();
            transport = transports.create(mailSession);
            transport.connect(
                    settings.host(),
                    settings.port(),
                    settings.authEnabled() ? settings.username() : null,
                    settings.authEnabled() ? settings.password() : null);
            sending = true;
            transport.sendMessage(message, message.getAllRecipients());
        } catch (MessagingException exception) {
            boolean rejected = definitiveRejection(exception);
            throw new MailDeliveryFailure(!sending || rejected, exception);
        } finally {
            // A QUIT/close timeout after sendMessage succeeded must not resend an accepted email.
            if (transport != null) {
                try {
                    transport.close();
                } catch (MessagingException ignored) {
                }
            }
        }
    }

    // Phân biệt lỗi SMTP bị từ chối rõ ràng với lỗi chưa xác định kết quả gửi.
    private static boolean definitiveRejection(MessagingException exception) {
        if (exception instanceof jakarta.mail.SendFailedException sent
                && sent.getValidSentAddresses() != null
                && sent.getValidSentAddresses().length > 0) {
            return false;
        }
        var visited =
                java.util.Collections.newSetFromMap(
                        new java.util.IdentityHashMap<Throwable, Boolean>());
        for (Throwable current = exception;
                current != null && visited.add(current);
                current =
                        current instanceof MessagingException next
                                ? next.getNextException()
                                : current.getCause()) {
            if (current instanceof SMTPSendFailedException smtp
                    && smtp.getReturnCode() >= 400
                    && smtp.getReturnCode() <= 599) {
                return true;
            }
            if (current instanceof SMTPAddressFailedException address
                    && address.getReturnCode() >= 400
                    && address.getReturnCode() <= 599) {
                return true;
            }
        }
        return false;
    }
}
