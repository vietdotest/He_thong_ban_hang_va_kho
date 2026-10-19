package vn.codegym.salesinventory.service;

import java.sql.*;
import java.time.*;
import java.util.*;
import javax.sql.DataSource;
import vn.codegym.salesinventory.dao.*;
import vn.codegym.salesinventory.dto.*;
import vn.codegym.salesinventory.model.UserStatus;
import vn.codegym.salesinventory.security.*;
import vn.codegym.salesinventory.validation.*;

/**
 * S4-10: Quản lý tài khoản đăng nhập gắn với đại lý trên cổng đặt hàng.
 */
public final class PortalAccountService {

    private final DataSource source;

    private final ActivationService activation;

    private final PasswordHasher hasher;

    private final Clock clock;

    public PortalAccountService(DataSource source, ActivationService activation) {
        this(source, activation, new BCryptPasswordHasher(), Clock.systemUTC());
    }

    public PortalAccountService(
            DataSource source, ActivationService activation, PasswordHasher hasher, Clock clock) {
        this.source = source;
        this.activation = activation;
        this.hasher = hasher;
        this.clock = clock;
    }

    // Kiểm tra quyền quản lý tài khoản cổng đại lý.
    private static Access manager(Connection connection, long actor) throws SQLException {
        var access = DealerService.access(connection, actor, "PORTAL_ACCOUNT_MANAGE");
        if (!access.roles().contains("SALES_MANAGER") || access.roles().contains("DEALER")) {
            throw new SecurityException();
        }
        return access;
    }

    public static boolean portal(Connection connection, long actor) throws SQLException {
        return "DEALER"
                .equals(
                        Sql.one(
                                        connection,
                                        "SELECT account_kind FROM users WHERE id=? FOR SHARE",
                                        actor)
                                .get("account_kind"));
    }

    // Kiểm tra quyền nghiệp vụ khi quản lý tài khoản cổng đại lý.
    public static Access access(Connection connection, long actor, String permission)
            throws SQLException {
        if (!portal(connection, actor)) {
            throw new SecurityException("Chỉ tài khoản cổng đại lý.");
        }
        var access = DealerService.access(connection, actor, permission);
        if (!access.roles().equals(Set.of("DEALER"))) {
            throw new SecurityException("Tài khoản cổng không được mang vai trò nội bộ.");
        }
        return access;
    }

    // Lấy đại lý trong phạm vi quản lý và khóa dữ liệu khi cần thay đổi.
    static Map<String, Object> scoped(
            Connection connection, long actor, Access access, long dealer, boolean lock)
            throws SQLException {
        access.require("PORTAL_ORDER_READ");
        if (!access.roles().equals(Set.of("DEALER")) || !portal(connection, actor)) {
            throw new SecurityException();
        }
        var links =
                Sql.query(
                        connection,
                        "SELECT dealer_id FROM dealer_accounts WHERE user_id=? FOR SHARE",
                        actor);
        if (links.isEmpty() || Sql.id(links.get(0).get("dealer_id")) != dealer) {
            throw new SecurityException("Dữ liệu không thuộc đại lý đang liên kết.");
        }
        return Sql.one(
                connection,
                "SELECT * FROM dealers WHERE id=?" + (lock ? " FOR UPDATE" : ""),
                dealer);
    }

    // Kiểm tra bản ghi đã được tham chiếu trong dữ liệu liên quan.
    static long linked(Connection connection, long actor) throws SQLException {
        access(connection, actor, "PORTAL_ORDER_READ");
        var rows =
                Sql.query(
                        connection,
                        "SELECT dealer_id FROM dealer_accounts WHERE user_id=? FOR SHARE",
                        actor);
        if (rows.isEmpty()) {
            throw new SecurityException("Tài khoản chưa có liên kết hợp lệ.");
        }
        return Sql.id(rows.get(0).get("dealer_id"));
    }

    // Lấy đại lý gắn với tài khoản đang đăng nhập cổng.
    public Map<String, Object> profile(long actor) {
        return Sql.transaction(
                source,
                connection -> {
                    var access = access(connection, actor, "PORTAL_ORDER_READ");
                    var d = scoped(connection, actor, access, linked(connection, actor), false);
                    long addressCount =
                            Sql.id(
                                    Sql.one(
                                                    connection,
                                                    "SELECT COUNT(*) total FROM dealer_addresses"
                                                        + " WHERE dealer_id=? AND status='ACTIVE'",
                                                    d.get("id"))
                                            .get("total"));
                    return Map.of(
                            "active_addresses",
                            addressCount,
                            "id",
                            d.get("id"),
                            "code",
                            d.get("code"),
                            "name",
                            d.get("name"),
                            "phone",
                            d.get("phone"),
                            "status",
                            d.get("status"),
                            "locked",
                            DealerStatusService.locked(d));
                });
    }

    // Kiểm tra tài khoản đích phù hợp để liên kết với đại lý.
    private static Map<String, Object> target(Connection connection, long user)
            throws SQLException {
        var rows =
                Sql.query(
                        connection,
                        "SELECT"
                            + " u.id,u.username,u.username_normalized,u.email,u.full_name,u.status,u.status_before_lock,u.version"
                            + " user_version,a.dealer_id,a.version FROM users u JOIN"
                            + " dealer_accounts a ON a.user_id=u.id WHERE u.id=? AND"
                            + " u.account_kind='DEALER' FOR UPDATE",
                        user);
        if (rows.isEmpty()) {
            throw new SecurityException("Chỉ quản lý tài khoản đại lý qua chức năng này.");
        }
        return rows.get(0);
    }

    // Tìm tài khoản cổng đại lý theo bộ lọc và phân trang.
    public PageResult<Map<String, Object>> search(
            long actor, String query, Long dealer, PageRequest request) {
        String q = query == null ? "" : query.trim();
        if (q.length() > 150) {
            q = q.substring(0, 150);
        }
        String term = "%" + q + "%";
        return Sql.snapshot(
                source,
                connection -> {
                    manager(connection, actor);
                    String from =
                            " FROM dealer_accounts a JOIN users u ON u.id=a.user_id JOIN dealers d"
                                + " ON d.id=a.dealer_id WHERE u.account_kind='DEALER' AND (? IS"
                                + " NULL OR a.dealer_id=?) AND (u.username LIKE ? OR u.full_name"
                                + " LIKE ? OR u.email LIKE ? OR d.code LIKE ? OR d.name LIKE ?)";
                    Object[] parameters = {dealer, dealer, term, term, term, term, term};
                    long total =
                            Sql.id(
                                    Sql.one(connection, "SELECT COUNT(*) total" + from, parameters)
                                            .get("total"));
                    var p = request.clamp(total);
                    var params = new ArrayList<Object>(Arrays.asList(parameters));
                    params.add(p.pageSize());
                    params.add(p.offset());
                    var rows =
                            Sql.query(
                                    connection,
                                    "SELECT u.id,u.username,u.email,u.full_name,u.status,u.version"
                                            + " user_version,a.dealer_id,a.version,d.code"
                                            + " dealer_code,d.name dealer_name"
                                            + from
                                            + " ORDER BY u.username,u.id LIMIT ? OFFSET ?",
                                    params.toArray());
                    return new PageResult<>(rows, total, p.page(), p.pageSize());
                });
    }

    private static void current(Map<String, Object> before, long version) {
        if (Sql.id(before.get("version")) != version) {
            throw FieldValidationException.field(
                    "form", "Liên kết tài khoản đã thay đổi. Hãy tải lại.");
        }
    }

    // Đổi liên kết tài khoản sang đại lý khác và ghi lý do thay đổi.
    public void relink(long actor, long user, long version, long dealer, String reason) {
        String why = CatalogValidation.text(reason, 1000, "Lý do đổi liên kết");
        Sql.transaction(
                source,
                connection -> {
                    var access = manager(connection, actor);
                    var before = target(connection, user);
                    current(before, version);
                    if (Sql.id(before.get("dealer_id")) == dealer) {
                        throw FieldValidationException.field(
                                "dealer", "Tài khoản đã liên kết đại lý này.");
                    }
                    for (long id :
                            new TreeSet<>(List.of(Sql.id(before.get("dealer_id")), dealer))) {
                        DealerService.scoped(connection, actor, access, id, true);
                    }
                    Sql.update(
                            connection,
                            "UPDATE dealer_accounts SET dealer_id=?,version=version+1 WHERE"
                                    + " user_id=?",
                            dealer,
                            user);
                    Sql.update(connection, "UPDATE users SET version=version+1 WHERE id=?", user);
                    new JdbcSessionRepository()
                            .revokeAllForUser(
                                    connection, user, clock.instant(), "DEALER_LINK_CHANGED");
                    Sql.insert(
                            connection,
                            "INSERT INTO"
                                + " dealer_account_link_history(user_id,old_dealer_id,new_dealer_id,actor_id,reason,account_version)"
                                + " VALUES(?,?,?,?,?,?)",
                            user,
                            before.get("dealer_id"),
                            dealer,
                            actor,
                            why,
                            version + 1);
                    AuditService.record(
                            connection,
                            actor,
                            "PORTAL_ACCOUNT_RELINKED",
                            "PORTAL_ACCOUNT",
                            user,
                            Map.of("dealer_id", before.get("dealer_id")),
                            Map.of("dealer_id", dealer, "reason", why));
                    return null;
                });
    }

    // Khóa hoặc mở tài khoản cổng đại lý theo phiên bản và lý do.
    public void status(long actor, long user, long version, boolean lock, String reason) {
        String why = CatalogValidation.text(reason, 1000, "Lý do khóa / mở tài khoản");
        Sql.transaction(
                source,
                connection -> {
                    manager(connection, actor);
                    var before = target(connection, user);
                    current(before, version);
                    String status = Sql.text(before.get("status"));
                    if (lock) {
                        if ("ADMIN_LOCKED".equals(status)) {
                            throw FieldValidationException.field("form", "Tài khoản đã khóa.");
                        }
                        Sql.update(
                                connection,
                                "UPDATE users SET"
                                    + " status_before_lock=status,status='ADMIN_LOCKED',lock_reason=?,locked_by=?,admin_locked_at=?,version=version+1"
                                    + " WHERE id=?",
                                why,
                                actor,
                                Timestamp.from(clock.instant()),
                                user);
                    } else {
                        if (!"ADMIN_LOCKED".equals(status)) {
                            throw FieldValidationException.field("form", "Tài khoản chưa bị khóa.");
                        }
                        String restored = Sql.text(before.get("status_before_lock"));
                        if (!Set.of("ACTIVE", "DISABLED", "PENDING_ACTIVATION")
                                .contains(restored)) {
                            restored = "DISABLED";
                        }
                        Sql.update(
                                connection,
                                "UPDATE users SET"
                                    + " status=?,status_before_lock=NULL,lock_reason=NULL,locked_by=NULL,admin_locked_at=NULL,failed_login_count=0,locked_until=NULL,version=version+1"
                                    + " WHERE id=?",
                                restored,
                                user);
                    }
                    Sql.update(
                            connection,
                            "UPDATE dealer_accounts SET version=version+1 WHERE user_id=?",
                            user);
                    new JdbcSessionRepository()
                            .revokeAllForUser(
                                    connection,
                                    user,
                                    clock.instant(),
                                    "PORTAL_ACCOUNT_STATUS_CHANGED");
                    AuditService.record(
                            connection,
                            actor,
                            lock ? "PORTAL_ACCOUNT_LOCKED" : "PORTAL_ACCOUNT_UNLOCKED",
                            "PORTAL_ACCOUNT",
                            user,
                            Map.of("status", status),
                            Map.of(
                                    "status",
                                    Sql.one(connection, "SELECT status FROM users WHERE id=?", user)
                                            .get("status"),
                                    "reason",
                                    why));
                    return null;
                });
    }

    // Ghi nhận bắt đầu gửi thư trước khi gọi dịch vụ SMTP.
    private String beginMail(long actor, long dealer, String username, String operation) {
        return Sql.transaction(
                source,
                connection -> {
                    String id = UUID.randomUUID().toString();
                    try {
                        Sql.update(
                                connection,
                                "INSERT INTO"
                                    + " portal_mail_attempts(id,actor_id,dealer_id,username_normalized,operation)"
                                    + " VALUES(?,?,?,?,?)",
                                id,
                                actor,
                                dealer,
                                username,
                                operation);
                    } catch (SQLException exception) {
                        if (exception.getErrorCode() == 1062) {
                            throw FieldValidationException.field(
                                    "email",
                                    "Lần gửi trước chưa rõ kết quả. Cần kiểm tra SMTP/QA trước khi"
                                            + " gửi lại.");
                        }
                        throw exception;
                    }
                    return id;
                });
    }

    // Lưu trạng thái thất bại của lần gửi thư để theo dõi lại.
    private void failedMail(String attempt, Throwable failure) {
        if (attempt != null) {
            Sql.transaction(
                    source,
                    connection -> {
                        Sql.update(
                                connection,
                                "UPDATE portal_mail_attempts SET state=?,finished_at=? WHERE id=?"
                                        + " AND state='STARTED'",
                                MailDeliveryFailure.isDefinitive(failure) ? "FAILED" : "REVIEW",
                                Timestamp.from(clock.instant()),
                                attempt);
                        return null;
                    });
        }
    }

    // Tạo tài khoản cổng đại lý từ dữ liệu đầu vào.
    public long create(
            long actor, long dealer, String username, String email, String name, String phone) {
        var command =
                new UserAccountCommand(
                        username, email, name, phone, "DEALER", UserStatus.PENDING_ACTIVATION, 0);
        var errors = new UserAccountValidator().validate(command);
        if (!errors.isEmpty()) {
            throw new FieldValidationException(errors);
        }
        String[] attempt = {null};
        try {
            return Sql.transaction(
                    source,
                    connection -> {
                        var access = manager(connection, actor);
                        DealerService.scoped(connection, actor, access, dealer, true);
                        if (activation == null) {
                            throw new IllegalStateException("Chưa cấu hình email kích hoạt.");
                        }
                        String password = new TemporaryPasswordGenerator().generate();
                        long id;
                        try {
                            id =
                                    new JdbcUserManagementRepository()
                                            .create(
                                                    connection,
                                                    command,
                                                    hasher.hash(password),
                                                    clock.instant());
                        } catch (SQLException exception) {
                            if (exception.getErrorCode() == 1062) {
                                throw FieldValidationException.field(
                                        "username",
                                        "Tên đăng nhập, email hoặc điện thoại đã được dùng.");
                            }
                            throw exception;
                        }
                        Sql.update(
                                connection,
                                "UPDATE users SET account_kind='DEALER' WHERE id=?",
                                id);
                        Sql.update(
                                connection,
                                "INSERT INTO user_roles(user_id,role_id) SELECT ?,id FROM roles"
                                        + " WHERE code='DEALER'",
                                id);
                        Sql.update(
                                connection,
                                "INSERT INTO dealer_accounts(user_id,dealer_id,issued_by)"
                                        + " VALUES(?,?,?)",
                                id,
                                dealer,
                                actor);
                        String token = activation.issue(connection, id);
                        AuditService.record(
                                connection,
                                actor,
                                "PORTAL_ACCOUNT_CREATED",
                                "PORTAL_ACCOUNT",
                                id,
                                null,
                                Map.of(
                                        "username",
                                        command.username(),
                                        "dealer_id",
                                        dealer,
                                        "status",
                                        "PENDING_ACTIVATION"));
                        attempt[0] =
                                beginMail(actor, dealer, command.normalizedUsername(), "CREATE");
                        activation.send(
                                command.email(),
                                command.fullName(),
                                command.username(),
                                password,
                                token);
                        Sql.update(
                                connection,
                                "UPDATE portal_mail_attempts SET"
                                        + " state='SUCCEEDED',user_id=?,finished_at=? WHERE id=?",
                                id,
                                Timestamp.from(clock.instant()),
                                attempt[0]);
                        return id;
                    });
        } catch (RuntimeException e) {
            failedMail(attempt[0], e);
            if (attempt[0] != null) {
                throw FieldValidationException.field(
                        "email",
                        MailDeliveryFailure.isDefinitive(e)
                                ? "SMTP từ chối: chưa tạo tài khoản, có thể thử lại."
                                : "Chưa rõ kết quả gửi. Không tự gửi lại; cần kiểm tra email"
                                        + " trước.");
            }
            throw e;
        }
    }

    // Tạo và gửi lại thông tin kích hoạt theo điều kiện cho phép.
    public void resend(long actor, long user, long version) {
        String[] attempt = {null};
        try {
            Sql.transaction(
                    source,
                    connection -> {
                        manager(connection, actor);
                        var before = target(connection, user);
                        current(before, version);
                        if (!"PENDING_ACTIVATION".equals(before.get("status"))) {
                            throw FieldValidationException.field(
                                    "form", "Chỉ gửi lại cho tài khoản đang chờ kích hoạt.");
                        }
                        if (activation == null) {
                            throw new IllegalStateException("Chưa cấu hình email kích hoạt.");
                        }
                        String password = new TemporaryPasswordGenerator().generate(),
                                token = activation.issue(connection, user);
                        Sql.update(
                                connection,
                                "UPDATE users SET"
                                    + " password_hash=?,must_change_password=TRUE,version=version+1"
                                    + " WHERE id=?",
                                hasher.hash(password),
                                user);
                        Sql.update(
                                connection,
                                "UPDATE dealer_accounts SET version=version+1 WHERE user_id=?",
                                user);
                        AuditService.record(
                                connection,
                                actor,
                                "PORTAL_ACTIVATION_RESENT",
                                "PORTAL_ACCOUNT",
                                user,
                                null,
                                Map.of("dealer_id", before.get("dealer_id")));
                        new JdbcSessionRepository()
                                .revokeAllForUser(
                                        connection,
                                        user,
                                        clock.instant(),
                                        "PORTAL_ACTIVATION_RESENT");
                        attempt[0] =
                                beginMail(
                                        actor,
                                        Sql.id(before.get("dealer_id")),
                                        Sql.text(before.get("username_normalized")),
                                        "RESEND");
                        activation.send(
                                Sql.text(before.get("email")),
                                Sql.text(before.get("full_name")),
                                Sql.text(before.get("username")),
                                password,
                                token);
                        Sql.update(
                                connection,
                                "UPDATE portal_mail_attempts SET"
                                        + " state='SUCCEEDED',user_id=?,finished_at=? WHERE id=?",
                                user,
                                Timestamp.from(clock.instant()),
                                attempt[0]);
                        return null;
                    });
        } catch (RuntimeException exception) {
            failedMail(attempt[0], exception);
            if (attempt[0] != null) {
                throw FieldValidationException.field(
                        "email",
                        MailDeliveryFailure.isDefinitive(exception)
                                ? "SMTP từ chối, mật khẩu/token cũ được giữ."
                                : "Chưa rõ kết quả gửi lại, cần kiểm tra email trước khi thử"
                                        + " tiếp.");
            }
            throw exception;
        }
    }

    // Lấy lịch sử phân bổ và đảo phân bổ của chứng từ.
    public PageResult<Map<String, Object>> linkHistory(long actor, long user, PageRequest request) {
        return Sql.snapshot(
                source,
                connection -> {
                    manager(connection, actor);
                    target(connection, user);
                    long total =
                            Sql.id(
                                    Sql.one(
                                                    connection,
                                                    "SELECT COUNT(*) total FROM"
                                                            + " dealer_account_link_history WHERE"
                                                            + " user_id=?",
                                                    user)
                                            .get("total"));
                    var p = request.clamp(total);
                    return new PageResult<>(
                            Sql.query(
                                    connection,
                                    "SELECT h.reason,h.changed_at,h.account_version,d1.code"
                                        + " old_code,d1.name old_name,d2.code new_code,d2.name"
                                        + " new_name,u.full_name actor_name FROM"
                                        + " dealer_account_link_history h JOIN dealers d1 ON"
                                        + " d1.id=h.old_dealer_id JOIN dealers d2 ON"
                                        + " d2.id=h.new_dealer_id JOIN users u ON u.id=h.actor_id"
                                        + " WHERE h.user_id=? ORDER BY h.id DESC LIMIT ? OFFSET ?",
                                    user,
                                    p.pageSize(),
                                    p.offset()),
                            total,
                            p.page(),
                            p.pageSize());
                });
    }
}
