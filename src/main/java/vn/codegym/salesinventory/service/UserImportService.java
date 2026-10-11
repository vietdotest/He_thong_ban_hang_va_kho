package vn.codegym.salesinventory.service;

import java.time.Clock;
import java.util.*;
import javax.sql.DataSource;
import vn.codegym.salesinventory.dao.Sql;
import vn.codegym.salesinventory.dto.*;
import vn.codegym.salesinventory.validation.UserImportValidator;

public final class UserImportService {
    public static final List<String> HEADERS = List.of("Tên đăng nhập", "Email", "Họ tên", "Điện thoại", "Vai trò", "Kho", "Địa bàn");
    private final DataSource source;
    private final UserManagementService users;
    private final Clock clock;

    public UserImportService(DataSource source, UserManagementService users) {
        this(source, users, Clock.systemUTC());
    }

    public UserImportService(DataSource source, UserManagementService users, Clock clock) {
        this.source = source;
        this.users = users;
        this.clock = clock;
    }

    record Input(UserAccountCommand command, Set<String> roles, Set<Long> warehouses, Set<Long> territories) { }

    private Input validate(List<String> row) {
        return Sql.transaction(source,c -> resolve(c,row));
    }
    static Input resolve(java.sql.Connection c,List<String> row) throws java.sql.SQLException {
        var input = UserImportValidator.validate(row);
        var command = input.command();
            for (String role : input.roles()) {
                if (Sql.query(c, "SELECT id FROM roles WHERE code=?", role).isEmpty())
                    throw new IllegalArgumentException("Vai trò không tồn tại: " + role);
            }
            Set<Long> warehouses = new LinkedHashSet<>(), territories = new LinkedHashSet<>();
            for (String code : input.warehouses()) {
                var found = Sql.query(c, "SELECT id FROM warehouses WHERE code=?", code);
                if (found.isEmpty()) throw new IllegalArgumentException("Kho không tồn tại: " + code);
                warehouses.add(Sql.id(found.get(0).get("id")));
            }
            for (String code : input.territories()) {
                var found = Sql.query(c, "SELECT id FROM territories WHERE code=?", code);
                if (found.isEmpty()) throw new IllegalArgumentException("Địa bàn không tồn tại: " + code);
                territories.add(Sql.id(found.get(0).get("id")));
            }
            AssignmentService.validate(-1, -2, input.roles(), warehouses);
            if (!Sql.query(c, "SELECT id FROM users WHERE username_normalized=? OR email_normalized=? OR phone_normalized=?",
                    command.normalizedUsername(), command.normalizedEmail(), command.normalizedPhone()).isEmpty())
                throw new IllegalArgumentException("Tên đăng nhập, email hoặc điện thoại đã được dùng.");
            return new Input(command, input.roles(), warehouses, territories);
    }

    public ImportPreview preview(long actor, byte[] bytes) {
        new AccessService(source).load(actor).require("USER_MANAGE");
        var rows = Xlsx.read(bytes);
        if (rows.isEmpty() || !rows.get(0).cells().equals(HEADERS) || !rows.get(0).error().isEmpty())
            throw new IllegalArgumentException("Tiêu đề không đúng tệp mẫu.");
        return Sql.transaction(source,c -> {
        List<ImportPreview.Line> lines = new ArrayList<>();
        Set<String> names = new HashSet<>(), emails = new HashSet<>(), phones = new HashSet<>();
        for (var row : rows.subList(1, rows.size())) {
            if (row.cells().stream().allMatch(String::isBlank)) continue;
            String error = row.error();
            try {
                if (!error.isEmpty()) throw new IllegalArgumentException(error);
                var in = resolve(c,row.cells());
                String name = in.command.normalizedUsername(), email = in.command.normalizedEmail(), phone = in.command.normalizedPhone();
                if (names.contains(name) || emails.contains(email) || phones.contains(phone))
                    throw new IllegalArgumentException("Dòng trùng trong tệp.");
                // Chỉ dòng hợp lệ mới giữ chỗ cho cả ba khóa duy nhất.
                names.add(name); emails.add(email); phones.add(phone);
            } catch (IllegalArgumentException e) { error = e.getMessage(); }
            lines.add(new ImportPreview.Line(row.number(), row.cells(), "Tạo mới", error));
        }
        if (lines.isEmpty()) throw new IllegalArgumentException("Tệp không có dòng dữ liệu.");
        return new ImportPreview(actor, lines, clock.instant());
        });
    }

    public List<ImportPreview.Line> confirm(long actor, ImportPreview preview, String token, AuthenticationContext context) {
        new AccessService(source).load(actor).require("USER_MANAGE");
        preview.claim(actor, token, clock.instant());
        List<ImportPreview.Line> result = new ArrayList<>();
        for (var row : preview.getLines()) {
            if (!row.isValid()) { result.add(row); continue; }
            new AccessService(source).load(actor).require("USER_MANAGE");
            String error = "";
            try {
                var in = validate(row.cells());
                var saved = users.createAssigned(in.command, actor, context, in.roles, in.warehouses, in.territories);
                if (saved.status() == UserManagementResult.Status.FORBIDDEN) throw new SecurityException();
                if (saved.status() != UserManagementResult.Status.SUCCESS) error = message(saved.status());
            } catch (IllegalArgumentException e) { error = e.getMessage(); }
            result.add(new ImportPreview.Line(row.number(), row.cells(), "Tạo mới", error));
        }
        return result;
    }

    private static String message(UserManagementResult.Status status) {
        return switch (status) {
            case EMAIL_DELIVERY_FAILED -> "Gửi email thất bại, chưa tạo tài khoản.";
            case DUPLICATE_USERNAME, DUPLICATE_EMAIL, DUPLICATE_PHONE -> "Dữ liệu trùng với tài khoản hiện có.";
            case INVALID_ROLE -> "Vai trò đã thay đổi. Hãy xem trước lại.";
            default -> throw new IllegalStateException("Kết quả tạo tài khoản không hợp lệ: " + status);
        };
    }
}
