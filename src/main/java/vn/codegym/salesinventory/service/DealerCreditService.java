package vn.codegym.salesinventory.service;

import java.math.BigDecimal;
import java.util.*;
import javax.sql.DataSource;
import vn.codegym.salesinventory.dao.Sql;
import vn.codegym.salesinventory.dto.*;
import vn.codegym.salesinventory.validation.*;

/**
 * S3-05: Cập nhật hạn mức, số ngày nợ và lưu lịch sử thay đổi của đại lý.
 */
public final class DealerCreditService {

    private final DataSource source;

    public DealerCreditService(DataSource source) {
        this.source = source;
    }

    // Lưu thông tin hạn mức công nợ của đại lý sau khi kiểm tra dữ liệu.
    public void save(
            long actor, long dealer, long version, String limit, String days, String reason) {
        var errors = new LinkedHashMap<String, String>();
        BigDecimal amount = null;
        int debtDays = 0;
        try {
            amount = CatalogValidation.decimal(limit, 4, false);
        } catch (IllegalArgumentException exception) {
            errors.put("limit", exception.getMessage());
        }
        try {
            if (days == null || !days.matches("[0-9]{1,10}")) {
                throw new NumberFormatException();
            }
            debtDays = Integer.parseInt(days);
        } catch (NumberFormatException e) {
            errors.put("days", "Số ngày phải là số nguyên không âm trong giới hạn dữ liệu.");
        }
        if (reason == null || reason.isBlank() || reason.trim().length() > 1000) {
            errors.put("reason", "Phải ghi lý do thay đổi, tối đa 1.000 ký tự.");
        }
        if (version < 1) {
            errors.put("form", "Phiên bản không hợp lệ.");
        }
        if (!errors.isEmpty()) {
            throw new FieldValidationException(errors);
        }
        final BigDecimal newLimit = amount;
        final int newDays = debtDays;
        Sql.transaction(
                source,
                connection -> {
                    var access = DealerService.access(connection, actor, "DEALER_CREDIT_MANAGE");
                    if (!access.roles().stream()
                            .anyMatch(Set.of("SALES_MANAGER", "ACCOUNTANT")::contains)) {
                        throw new SecurityException(
                                "Chỉ Kế toán và Quản lý kinh doanh được sửa tín dụng.");
                    }
                    var before = DealerService.scoped(connection, actor, access, dealer, true);
                    if (Sql.id(before.get("version")) != version) {
                        throw FieldValidationException.field(
                                "form", "Hồ sơ đã thay đổi. Hãy tải lại trước khi sửa hạn mức.");
                    }
                    Sql.update(
                            connection,
                            "UPDATE dealers SET credit_limit=?,debt_days=?,version=version+1 WHERE"
                                    + " id=?",
                            newLimit,
                            newDays,
                            dealer);
                    Sql.insert(
                            connection,
                            "INSERT INTO"
                                + " dealer_credit_history(dealer_id,actor_id,old_limit,new_limit,old_days,new_days,reason,dealer_version)"
                                + " VALUES(?,?,?,?,?,?,?,?)",
                            dealer,
                            actor,
                            before.get("credit_limit"),
                            newLimit,
                            before.get("debt_days"),
                            newDays,
                            reason.trim(),
                            version + 1);
                    var after =
                            new LinkedHashMap<>(
                                    Sql.one(
                                            connection,
                                            "SELECT id,credit_limit,debt_days,version FROM dealers"
                                                    + " WHERE id=?",
                                            dealer));
                    after.put("reason", reason.trim());
                    AuditService.record(
                            connection,
                            actor,
                            "DEALER_CREDIT_CHANGED",
                            "DEALER",
                            dealer,
                            before,
                            after);
                    return null;
                });
    }

    // Lấy lịch sử thay đổi hạn mức công nợ của đại lý.
    public PageResult<Map<String, Object>> history(long actor, long dealer, PageRequest request) {
        return Sql.transaction(
                source,
                connection -> {
                    var access = DealerService.access(connection, actor, "DEALER_READ");
                    DealerService.scoped(connection, actor, access, dealer, false);
                    long total =
                            Sql.id(
                                    Sql.one(
                                                    connection,
                                                    "SELECT COUNT(*) total FROM"
                                                            + " dealer_credit_history WHERE"
                                                            + " dealer_id=?",
                                                    dealer)
                                            .get("total"));
                    var p = request.clamp(total);
                    var rows =
                            Sql.query(
                                    connection,
                                    "SELECT"
                                        + " h.id,h.old_limit,h.new_limit,h.old_days,h.new_days,h.reason,h.dealer_version,h.changed_at,u.full_name"
                                        + " actor_name FROM dealer_credit_history h JOIN users u ON"
                                        + " u.id=h.actor_id WHERE h.dealer_id=? ORDER BY"
                                        + " h.changed_at DESC,h.id DESC LIMIT ? OFFSET ?",
                                    dealer,
                                    p.pageSize(),
                                    p.offset());
                    return new PageResult<>(rows, total, p.page(), p.pageSize());
                });
    }
}
