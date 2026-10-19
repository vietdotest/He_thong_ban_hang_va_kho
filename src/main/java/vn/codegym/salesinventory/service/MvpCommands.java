package vn.codegym.salesinventory.service;

import java.math.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.*;
import java.time.*;
import java.util.*;
import vn.codegym.salesinventory.dao.Sql;
import vn.codegym.salesinventory.validation.CommandConflictException;

/**
 * Dùng chung: Chặn ghi lặp, kiểm phiên bản và cấp số chứng từ trong giao dịch của bên gọi.
 */
public final class MvpCommands {

    private MvpCommands() {}

    // Chứa mã yêu cầu và kết quả cũ để chống ghi lặp chứng từ.
    public record Ticket(long actor, String operation, String key, Long replayId) {}

    // Đăng ký mã yêu cầu và kiểm tra dữ liệu để chống ghi lặp.
    public static Ticket begin(
            Connection connection, long actor, String operation, String key, String input)
            throws SQLException {
        try {
            key = UUID.fromString(key).toString();
        } catch (Exception exception) {
            throw new IllegalArgumentException("Mã chống ghi lặp không hợp lệ.");
        }
        String hash;
        try {
            hash =
                    HexFormat.of()
                            .formatHex(
                                    MessageDigest.getInstance("SHA-256")
                                            .digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        Sql.update(
                connection,
                "INSERT IGNORE INTO mvp_commands(actor_id,operation,request_key,input_hash)"
                        + " VALUES(?,?,?,?)",
                actor,
                operation,
                key,
                hash);
        var row =
                Sql.one(
                        connection,
                        "SELECT input_hash,object_id FROM mvp_commands WHERE actor_id=? AND"
                                + " operation=? AND request_key=? FOR UPDATE",
                        actor,
                        operation,
                        key);
        if (!hash.equals(row.get("input_hash"))) {
            throw new CommandConflictException(
                    "Mã xác nhận đã dùng cho dữ liệu khác. Hãy tải lại chứng từ.");
        }
        return new Ticket(
                actor,
                operation,
                key,
                row.get("object_id") == null ? null : Sql.id(row.get("object_id")));
    }

    // Lưu kết quả gắn với mã yêu cầu để lần gửi lại dùng cùng kết quả.
    public static long finish(Connection connection, Ticket ticket, long id) throws SQLException {
        Sql.update(
                connection,
                "UPDATE mvp_commands SET object_id=? WHERE actor_id=? AND operation=? AND"
                        + " request_key=?",
                id,
                ticket.actor,
                ticket.operation,
                ticket.key);
        return id;
    }

    // Kiểm tra phiên bản để phát hiện dữ liệu đã bị người khác thay đổi.
    public static void version(Map<String, Object> row, long expected) {
        if (Sql.id(row.get("version")) != expected) {
            throw new CommandConflictException(
                    "Chứng từ đã thay đổi. Hãy tải lại trước khi xác nhận.");
        }
    }

    // Lấy trạng thái đã lưu của yêu cầu cũ để trả lại khi gửi lặp.
    public static String resultStatus(Connection connection, Ticket ticket) throws SQLException {
        String result =
                Sql.text(
                        Sql.one(
                                        connection,
                                        "SELECT result_status FROM mvp_commands WHERE actor_id=?"
                                                + " AND operation=? AND request_key=?",
                                        ticket.actor,
                                        ticket.operation,
                                        ticket.key)
                                .get("result_status"));
        if (result.isEmpty()) {
            throw new CommandConflictException(
                    "Yêu cầu cũ chưa có kết quả lưu; hãy tải lại chứng từ.");
        }
        return result;
    }

    // Lưu kết quả gắn với mã yêu cầu để lần gửi lại dùng cùng kết quả.
    public static long finish(Connection connection, Ticket ticket, long id, String status)
            throws SQLException {
        Sql.update(
                connection,
                "UPDATE mvp_commands SET result_status=? WHERE actor_id=? AND operation=? AND"
                        + " request_key=?",
                status,
                ticket.actor,
                ticket.operation,
                ticket.key);
        return finish(connection, ticket, id);
    }

    // Kiểm tra số lượng dương và giới hạn số chữ số thập phân.
    public static BigDecimal quantity(BigDecimal value) {
        if (value == null
                || value.signum() <= 0
                || value.stripTrailingZeros().scale() > 12
                || value.precision() - value.scale() > 26) {
            throw new IllegalArgumentException("Số lượng phải dương, tối đa 12 chữ số thập phân.");
        }
        return value.setScale(12, RoundingMode.UNNECESSARY);
    }

    public static BigDecimal money(BigDecimal value) {
        return value.setScale(4, RoundingMode.HALF_UP);
    }

    public static BigDecimal decimal(Object value) {
        return value == null ? BigDecimal.ZERO : ((BigDecimal) value);
    }

    // Phân bổ giá trị theo tỷ lệ số lượng và làm tròn theo quy tắc tiền.
    public static BigDecimal share(BigDecimal total, BigDecimal quantity, BigDecimal denominator) {
        if (denominator.signum() <= 0) {
            throw new IllegalArgumentException("Số lượng nguồn không hợp lệ.");
        }
        return total.multiply(quantity).divide(denominator, 4, RoundingMode.HALF_UP);
    }

    // Chuẩn hóa chuỗi bắt buộc và kiểm tra độ dài tối đa.
    public static String text(String value, int max, String label) {
        String clean = value == null ? "" : value.trim();
        if (clean.isEmpty() || clean.length() > max) {
            throw new IllegalArgumentException(
                    label + " không được trống hoặc vượt " + max + " ký tự.");
        }
        return clean;
    }

    public static Timestamp timestamp(Instant now) {
        return Timestamp.from(now);
    }

    public static LocalDate date(Instant now) {
        return now.atZone(vn.codegym.salesinventory.config.VietnamTime.ZONE).toLocalDate();
    }

    // Cấp số chứng từ tiếp theo theo loại và năm trong cùng giao dịch.
    public static String nextNumber(Connection connection, String kind, LocalDate date)
            throws SQLException {
        Sql.update(
                connection,
                "INSERT IGNORE INTO mvp_sequences(kind,document_year,next_value) VALUES(?,?,1)",
                kind,
                date.getYear());
        long next =
                Sql.id(
                        Sql.one(
                                        connection,
                                        "SELECT next_value FROM mvp_sequences WHERE kind=? AND"
                                                + " document_year=? FOR UPDATE",
                                        kind,
                                        date.getYear())
                                .get("next_value"));
        Sql.update(
                connection,
                "UPDATE mvp_sequences SET next_value=next_value+1 WHERE kind=? AND document_year=?",
                kind,
                date.getYear());
        return kind + "-" + date.getYear() + "-" + String.format(Locale.ROOT, "%06d", next);
    }
}
