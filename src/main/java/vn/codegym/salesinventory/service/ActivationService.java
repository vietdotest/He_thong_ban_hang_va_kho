package vn.codegym.salesinventory.service;

import java.sql.Connection;
import java.time.*;
import javax.sql.DataSource;
import vn.codegym.salesinventory.dao.*;
import vn.codegym.salesinventory.security.*;

/**
 * S1-08: Kích hoạt tài khoản bằng mã dùng một lần và hỗ trợ gửi lại email.
 */
public final class ActivationService {

    private final DataSource source;

    private final Clock clock;

    private final String baseUrl;

    private final MailService mail;

    public ActivationService(DataSource source, Clock clock, String baseUrl, MailService mail) {
        this.source = source;
        this.clock = clock;
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.mail = mail;
    }

    // Phát hành chứng từ sau khi kiểm tra dữ liệu nguồn.
    public String issue(Connection connection, long userId) throws java.sql.SQLException {
        Sql.update(
                connection,
                "UPDATE activation_tokens SET used_at=? WHERE user_id=? AND used_at IS NULL",
                java.sql.Timestamp.from(clock.instant()),
                userId);
        String token = new SecureTokenGenerator().generate();
        Sql.insert(
                connection,
                "INSERT INTO activation_tokens(user_id,token_hash,expires_at) VALUES(?,?,?)",
                userId,
                TokenHashing.sha256(token),
                java.sql.Timestamp.from(clock.instant().plus(Duration.ofHours(24))));
        return token;
    }

    public void send(String email, String name, String username, String password, String token) {
        mail.sendActivation(email, name, username, password, baseUrl + "/activate?token=" + token);
    }

    // Xác nhận mã kích hoạt và chuyển tài khoản sang trạng thái hoạt động.
    public boolean activate(String token) {
        if (token == null || token.isBlank() || token.length() > 128) {
            return false;
        }
        return Sql.transaction(
                source,
                connection -> {
                    var tokens =
                            Sql.query(
                                    connection,
                                    "SELECT * FROM activation_tokens WHERE token_hash=? FOR UPDATE",
                                    TokenHashing.sha256(token));
                    if (tokens.isEmpty()) {
                        return false;
                    }
                    var t = tokens.get(0);
                    if (t.get("used_at") != null
                            || !clock.instant().isBefore(Sql.instant(t.get("expires_at")))) {
                        return false;
                    }
                    int changed =
                            Sql.update(
                                    connection,
                                    "UPDATE users SET status='ACTIVE',version=version+1 WHERE id=?"
                                            + " AND status='PENDING_ACTIVATION'",
                                    t.get("user_id"));
                    if (changed != 1) {
                        return false;
                    }
                    Sql.update(
                            connection,
                            "UPDATE activation_tokens SET used_at=? WHERE id=?",
                            java.sql.Timestamp.from(clock.instant()),
                            t.get("id"));
                    new JdbcAuditLogRepository()
                            .record(
                                    connection,
                                    Sql.id(t.get("user_id")),
                                    "ACCOUNT_ACTIVATED",
                                    "activationCompleted=true",
                                    null,
                                    null,
                                    clock.instant());
                    return true;
                });
    }

    // Tạo và gửi lại thông tin kích hoạt theo điều kiện cho phép.
    public void resend(long actor, long userId) {
        new AccessService(source).load(actor).require("USER_MANAGE");
        Sql.transaction(
                source,
                connection -> {
                    var user =
                            Sql.one(
                                    connection,
                                    "SELECT username,email,full_name,status,account_kind FROM users"
                                            + " WHERE id=? FOR UPDATE",
                                    userId);
                    if ("DEALER".equals(user.get("account_kind"))) {
                        throw new SecurityException(
                                "Gửi lại kích hoạt đại lý qua chức năng tài khoản cổng.");
                    }
                    if (!Sql.text(user.get("status")).equals("PENDING_ACTIVATION")) {
                        throw new IllegalArgumentException(
                                "Chỉ gửi lại kích hoạt cho tài khoản đang chờ.");
                    }
                    String password = new TemporaryPasswordGenerator().generate();
                    Sql.update(
                            connection,
                            "UPDATE users SET"
                                    + " password_hash=?,must_change_password=TRUE,version=version+1"
                                    + " WHERE id=?",
                            new BCryptPasswordHasher().hash(password),
                            userId);
                    String token = issue(connection, userId);
                    send(
                            Sql.text(user.get("email")),
                            Sql.text(user.get("full_name")),
                            Sql.text(user.get("username")),
                            password,
                            token);
                    new JdbcAuditLogRepository()
                            .record(
                                    connection,
                                    actor,
                                    "ACTIVATION_RESENT",
                                    "targetUserId=" + userId,
                                    null,
                                    null,
                                    clock.instant());
                    return null;
                });
    }
}
