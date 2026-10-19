package vn.codegym.salesinventory.service;

import java.sql.*;
import java.util.*;
import javax.sql.DataSource;
import vn.codegym.salesinventory.dao.Sql;
import vn.codegym.salesinventory.dto.*;

/**
 * S3-06, hỗ trợ S1-10: Xem trước và bàn giao đại lý giữa các nhân viên kinh doanh.
 */
public final class DealerHandoverService {

    private final DataSource source;

    public DealerHandoverService(DataSource source) {
        this.source = source;
    }

    // Kiểm tra người thao tác có quyền thực hiện nghiệp vụ.
    private static void authorize(Connection connection, long actor) throws SQLException {
        var access = DealerService.access(connection, actor, "DEALER_HANDOVER");
        if (!access.roles().contains("SALES_MANAGER") || !DealerService.all(access)) {
            throw new SecurityException("Chỉ Quản lý kinh doanh được bàn giao đại lý.");
        }
    }

    private static void target(Connection connection, long staff) throws SQLException {
        if (Sql.query(
                        connection,
                        "SELECT u.id FROM users u JOIN user_roles ur ON ur.user_id=u.id JOIN roles"
                            + " r ON r.id=ur.role_id WHERE u.id=? AND u.status='ACTIVE' AND r.code"
                            + " IN ('SALES','SALES_MANAGER') FOR SHARE",
                        staff)
                .isEmpty()) {
            throw new IllegalArgumentException(
                    "Người nhận bàn giao phải là nhân viên kinh doanh đang hoạt động.");
        }
    }

    // Lấy các lựa chọn cần dùng trên biểu mẫu bàn giao đại lý.
    public Map<String, List<Map<String, Object>>> options(long actor) {
        return Sql.transaction(
                source,
                connection -> {
                    authorize(connection, actor);
                    return Map.of(
                            "fromStaff",
                            Sql.query(
                                    connection,
                                    "SELECT DISTINCT u.id,u.username code,u.full_name name FROM"
                                        + " users u JOIN dealers d ON d.primary_staff_id=u.id ORDER"
                                        + " BY u.full_name,u.id"),
                            "toStaff",
                            Sql.query(
                                    connection,
                                    "SELECT DISTINCT u.id,u.username code,u.full_name name FROM"
                                        + " users u JOIN user_roles ur ON ur.user_id=u.id JOIN"
                                        + " roles r ON r.id=ur.role_id WHERE u.status='ACTIVE' AND"
                                        + " r.code IN ('SALES','SALES_MANAGER') ORDER BY"
                                        + " u.full_name,u.id"),
                            "territories",
                            Sql.query(
                                    connection,
                                    "SELECT id,code,name FROM territories ORDER BY name,id"));
                });
    }

    // Kiểm tra dữ liệu và tạo bản xem trước để người dùng xác nhận.
    public String preview(long actor, long from, long to, Long territory, String reason) {
        return preview(actor, from, to, territory, reason, null);
    }

    // Kiểm tra dữ liệu và tạo bản xem trước để người dùng xác nhận.
    public String preview(
            long actor, long from, long to, Long territory, String reason, Long dealer) {
        if (from <= 0 || to <= 0 || from == to) {
            throw new IllegalArgumentException("Chọn hai người phụ trách khác nhau.");
        }
        if (reason == null || reason.isBlank() || reason.trim().length() > 1000) {
            throw new IllegalArgumentException("Phải ghi lý do bàn giao, tối đa 1.000 ký tự.");
        }
        return Sql.transaction(
                source,
                connection -> {
                    authorize(connection, actor);
                    Sql.one(
                            connection,
                            "SELECT name FROM catalog_locks WHERE name='DEALER_ASSIGNMENTS' FOR"
                                    + " UPDATE");
                    target(connection, to);
                    Sql.one(connection, "SELECT id FROM users WHERE id=? FOR SHARE", from);
                    String key = UUID.randomUUID().toString();
                    long batch =
                            Sql.insert(
                                    connection,
                                    "INSERT INTO"
                                        + " dealer_handover_batches(batch_key,actor_id,from_staff_id,to_staff_id,territory_id,dealer_id,reason,expires_at)"
                                        + " VALUES(?,?,?,?,?,?,?,DATE_ADD(CURRENT_TIMESTAMP(6),INTERVAL"
                                        + " 30 MINUTE))",
                                    key,
                                    actor,
                                    from,
                                    to,
                                    territory,
                                    dealer,
                                    reason.trim());
                    int count =
                            Sql.update(
                                    connection,
                                    "INSERT INTO"
                                        + " dealer_handover_items(batch_id,dealer_id,before_version,from_staff_id,territory_id)"
                                        + " SELECT ?,id,version,primary_staff_id,territory_id FROM"
                                        + " dealers WHERE primary_staff_id=? AND (? IS NULL OR"
                                        + " territory_id=?) AND (? IS NULL OR id=?) ORDER BY id",
                                    batch,
                                    from,
                                    territory,
                                    territory,
                                    dealer,
                                    dealer);
                    if (count == 0) {
                        throw new IllegalArgumentException("Không có đại lý phù hợp để bàn giao.");
                    }
                    Sql.update(
                            connection,
                            "UPDATE dealer_handover_batches SET total_items=? WHERE id=?",
                            count,
                            batch);
                    return key;
                });
    }

    // Lấy thông tin đợt xử lý theo mã.
    public Map<String, Object> batch(long actor, String key) {
        return Sql.transaction(
                source,
                connection -> {
                    authorize(connection, actor);
                    return Sql.one(
                            connection,
                            "SELECT b.*,u.full_name from_name,v.full_name to_name FROM"
                                + " dealer_handover_batches b JOIN users u ON u.id=b.from_staff_id"
                                + " JOIN users v ON v.id=b.to_staff_id WHERE b.batch_key=? AND"
                                + " (b.actor_id=? OR b.status='CONFIRMED')",
                            key,
                            actor);
                });
    }

    // Lấy các dòng chi tiết phù hợp với bộ lọc và phân trang.
    public PageResult<Map<String, Object>> rows(long actor, String key, PageRequest request) {
        return Sql.transaction(
                source,
                connection -> {
                    authorize(connection, actor);
                    var batch =
                            Sql.one(
                                    connection,
                                    "SELECT id,total_items FROM dealer_handover_batches WHERE"
                                            + " batch_key=? AND (actor_id=? OR status='CONFIRMED')",
                                    key,
                                    actor);
                    long total = Sql.id(batch.get("total_items"));
                    var p = request.clamp(total);
                    return new PageResult<>(
                            Sql.query(
                                    connection,
                                    "SELECT"
                                        + " d.id,d.code,d.name,d.version,h.before_version,h.after_version"
                                        + " FROM dealer_handover_items h JOIN dealers d ON"
                                        + " d.id=h.dealer_id WHERE h.batch_id=? ORDER BY"
                                        + " d.code,d.id LIMIT ? OFFSET ?",
                                    batch.get("id"),
                                    p.pageSize(),
                                    p.offset()),
                            total,
                            p.page(),
                            p.pageSize());
                });
    }

    // Xác nhận dữ liệu xem trước và thực hiện thao tác đã chọn.
    public int confirm(long actor, String key) {
        return Sql.transaction(
                source,
                connection -> {
                    authorize(connection, actor);
                    Sql.one(
                            connection,
                            "SELECT name FROM catalog_locks WHERE name='DEALER_ASSIGNMENTS' FOR"
                                    + " UPDATE");
                    var batch =
                            Sql.one(
                                    connection,
                                    "SELECT b.*,b.expires_at<CURRENT_TIMESTAMP(6) expired FROM"
                                            + " dealer_handover_batches b WHERE batch_key=? AND"
                                            + " actor_id=? FOR UPDATE",
                                    key,
                                    actor);
                    if ("CONFIRMED".equals(batch.get("status"))) {
                        return ((Number) batch.get("total_items")).intValue();
                    }
                    if (DealerAddressService.isDefault(batch.get("expired"))) {
                        throw new IllegalArgumentException("Bản xem trước hết hạn. Hãy tạo lại.");
                    }
                    long from = Sql.id(batch.get("from_staff_id")),
                            to = Sql.id(batch.get("to_staff_id"));
                    target(connection, to);
                    long batchId = Sql.id(batch.get("id"));
                    var rows =
                            Sql.query(
                                    connection,
                                    "SELECT d.*,h.before_version,h.from_staff_id FROM"
                                        + " dealer_handover_items h JOIN dealers d ON"
                                        + " d.id=h.dealer_id WHERE h.batch_id=? ORDER BY d.id FOR"
                                        + " UPDATE",
                                    batchId);
                    long current =
                            Sql.id(
                                    Sql.one(
                                                    connection,
                                                    "SELECT COUNT(*) total FROM dealers WHERE"
                                                        + " primary_staff_id=? AND (? IS NULL OR"
                                                        + " territory_id=?) AND (? IS NULL OR"
                                                        + " id=?)",
                                                    from,
                                                    batch.get("territory_id"),
                                                    batch.get("territory_id"),
                                                    batch.get("dealer_id"),
                                                    batch.get("dealer_id"))
                                            .get("total"));
                    if (rows.size() != Sql.id(batch.get("total_items")) || current != rows.size()) {
                        throw new IllegalArgumentException(
                                "Phạm vi đại lý đã thay đổi. Hãy xem trước lại.");
                    }
                    for (var before : rows) {
                        long id = Sql.id(before.get("id"));
                        if (Sql.id(before.get("version")) != Sql.id(before.get("before_version"))
                                || Sql.id(before.get("primary_staff_id")) != from) {
                            throw new IllegalArgumentException(
                                    "Đại lý "
                                            + before.get("code")
                                            + " đã thay đổi. Hãy xem trước lại.");
                        }
                        Sql.update(
                                connection,
                                "UPDATE dealers SET primary_staff_id=?,version=version+1 WHERE"
                                        + " id=?",
                                to,
                                id);
                        DealerService.syncReference(connection, id, to);
                        Sql.update(
                                connection,
                                "UPDATE handover_warnings SET resolved_at=CURRENT_TIMESTAMP(6)"
                                    + " WHERE user_id=? AND dealer_reference=? AND resolved_at IS"
                                    + " NULL",
                                from,
                                "DEALER:" + id);
                        Sql.update(
                                connection,
                                "UPDATE dealer_handover_items SET after_version=? WHERE batch_id=?"
                                        + " AND dealer_id=?",
                                Sql.id(before.get("version")) + 1,
                                batchId,
                                id);
                        var after =
                                new LinkedHashMap<>(
                                        Sql.one(
                                                connection,
                                                "SELECT * FROM dealers WHERE id=?",
                                                id));
                        after.put("reason", batch.get("reason"));
                        after.put("batch_key", key);
                        AuditService.record(
                                connection,
                                actor,
                                "DEALER_HANDED_OVER",
                                "DEALER",
                                id,
                                before,
                                after);
                    }
                    Sql.update(
                            connection,
                            "UPDATE dealer_handover_batches SET"
                                    + " status='CONFIRMED',confirmed_at=CURRENT_TIMESTAMP(6) WHERE"
                                    + " id=?",
                            batchId);
                    return rows.size();
                });
    }

    // Lấy lịch sử thay đổi bàn giao đại lý.
    public PageResult<Map<String, Object>> history(long actor, PageRequest request) {
        return Sql.transaction(
                source,
                connection -> {
                    authorize(connection, actor);
                    long total =
                            Sql.id(
                                    Sql.one(
                                                    connection,
                                                    "SELECT COUNT(*) total FROM"
                                                            + " dealer_handover_batches WHERE"
                                                            + " status='CONFIRMED'")
                                            .get("total"));
                    var p = request.clamp(total);
                    return new PageResult<>(
                            Sql.query(
                                    connection,
                                    "SELECT"
                                        + " b.batch_key,b.reason,b.total_items,b.confirmed_at,u.full_name"
                                        + " from_name,v.full_name to_name,a.full_name actor_name"
                                        + " FROM dealer_handover_batches b JOIN users u ON"
                                        + " u.id=b.from_staff_id JOIN users v ON v.id=b.to_staff_id"
                                        + " JOIN users a ON a.id=b.actor_id WHERE"
                                        + " b.status='CONFIRMED' ORDER BY b.confirmed_at DESC,b.id"
                                        + " DESC LIMIT ? OFFSET ?",
                                    p.pageSize(),
                                    p.offset()),
                            total,
                            p.page(),
                            p.pageSize());
                });
    }
}
