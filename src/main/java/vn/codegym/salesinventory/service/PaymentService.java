package vn.codegym.salesinventory.service;

import java.math.BigDecimal;
import java.sql.*;
import java.time.*;
import java.util.*;
import javax.sql.DataSource;
import vn.codegym.salesinventory.dao.Sql;

/**
 * S7-02, S7-09 (MVP): Ghi phiếu thu, đối trừ công nợ và đảo phiếu bằng chứng từ mới có lý do.
 */
public final class PaymentService {

    private final DataSource source;

    private final Clock clock;

    public PaymentService(DataSource source) {
        this(source, Clock.systemUTC());
    }

    public PaymentService(DataSource source, Clock clock) {
        this.source = source;
        this.clock = clock;
    }

    // Chứa dữ liệu đầu vào của một phiếu thu.
    public record Input(BigDecimal amount, LocalDate date, String method, String note) {}

    // Ghi phiếu thu và phân bổ tiền vào các hóa đơn còn nợ.
    public long record(
            long actor, long dealer, long expectedVersion, String requestKey, Input input) {
        if (input == null
                || input.amount == null
                || input.amount.signum() <= 0
                || input.amount.stripTrailingZeros().scale() > 4
                || input.amount.precision() - input.amount.scale() > 15) {
            throw new IllegalArgumentException(
                    "Số tiền thu phải dương, tối đa 4 chữ số thập phân.");
        }
        BigDecimal amount = MvpCommands.money(input.amount);
        if (input.date == null
                || input.date.getYear() < 1000
                || input.date.getYear() > 9999
                || input.date.isAfter(MvpCommands.date(clock.instant()))) {
            throw new IllegalArgumentException("Ngày thu không hợp lệ hoặc ở tương lai.");
        }
        if (!Set.of("CASH", "BANK_TRANSFER").contains(input.method == null ? "" : input.method)) {
            throw new IllegalArgumentException("Chọn tiền mặt hoặc chuyển khoản.");
        }
        String note = input.note == null ? "" : input.note.trim();
        if (note.length() > 1000) {
            throw new IllegalArgumentException("Ghi chú tối đa 1000 ký tự.");
        }
        return Sql.transaction(
                source,
                connection -> {
                    MvpAccess.accountant(connection, actor, "PAYMENT_WRITE");
                    var customer =
                            MvpAccess.dealer(connection, actor, "PAYMENT_WRITE", dealer, true);
                    var command =
                            MvpCommands.begin(
                                    connection,
                                    actor,
                                    "PAYMENT_RECORD",
                                    requestKey,
                                    dealer
                                            + ":"
                                            + expectedVersion
                                            + ":"
                                            + amount
                                            + ":"
                                            + input.date
                                            + ":"
                                            + input.method
                                            + ":"
                                            + note);
                    if (command.replayId() != null) {
                        return command.replayId();
                    }
                    MvpCommands.version(customer, expectedVersion);
                    var now = clock.instant();
                    FinancialAllocations.lock(connection, dealer);
                    var credit = CreditService.snapshot(connection, dealer, now);
                    if (amount.compareTo(credit.outstanding()) > 0) {
                        throw new IllegalArgumentException(
                                "Thu vượt số còn phải thu "
                                        + credit.outstanding().stripTrailingZeros().toPlainString()
                                        + " đ.");
                    }
                    var addresses =
                            Sql.query(
                                    connection,
                                    "SELECT address FROM dealer_addresses WHERE dealer_id=? AND"
                                        + " status='ACTIVE' ORDER BY is_default DESC,id LIMIT 1",
                                    dealer);
                    String address =
                            addresses.isEmpty() ? "" : Sql.text(addresses.get(0).get("address"));
                    String number = MvpCommands.nextNumber(connection, "PT", MvpCommands.date(now));
                    long payment =
                            Sql.insert(
                                    connection,
                                    "INSERT INTO"
                                        + " mvp_payments(dealer_id,number,amount,paid_date,method,note,recorded_by,recorded_at,dealer_code_snapshot,dealer_name_snapshot,address_snapshot)"
                                        + " VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                                    dealer,
                                    number,
                                    amount,
                                    input.date,
                                    input.method,
                                    note,
                                    actor,
                                    MvpCommands.timestamp(now),
                                    customer.get("code"),
                                    customer.get("name"),
                                    address);
                    FinancialAllocations.reallocate(
                            connection, dealer, "PAYMENT_RECORD", payment, now);
                    AuditService.record(
                            connection,
                            actor,
                            "PAYMENT_RECORDED",
                            "PAYMENT",
                            payment,
                            null,
                            Sql.one(connection, "SELECT * FROM mvp_payments WHERE id=?", payment));
                    return MvpCommands.finish(connection, command, payment);
                });
    }

    // Lấy các đại lý còn công nợ trong phạm vi kế toán được xem.
    public List<Map<String, Object>> debtors(long actor) {
        return Sql.snapshot(
                source,
                connection -> {
                    MvpAccess.accountant(connection, actor, "PAYMENT_WRITE");
                    var result = new ArrayList<Map<String, Object>>();
                    for (var dealer :
                            Sql.query(
                                    connection,
                                    "SELECT id,code,name,version FROM dealers ORDER BY name,id")) {
                        var snapshot =
                                CreditService.snapshot(
                                        connection, Sql.id(dealer.get("id")), clock.instant());
                        if (snapshot.outstanding().signum() > 0) {
                            dealer.putAll(snapshot.display());
                            result.add(dealer);
                        }
                    }
                    return result;
                });
    }

    // Đảo phiếu thu bằng chứng từ mới và giữ nguyên lịch sử cũ.
    public long reverse(long actor, long payment, long version, String requestKey, String reason) {
        String why = MvpCommands.text(reason, 1000, "Lý do đảo toàn phiếu");
        return Sql.transaction(
                source,
                connection -> {
                    MvpAccess.accountant(connection, actor, "PAYMENT_WRITE");
                    var known =
                            Sql.one(
                                    connection,
                                    "SELECT dealer_id FROM mvp_payments WHERE id=?",
                                    payment);
                    long dealer = Sql.id(known.get("dealer_id"));
                    MvpAccess.dealer(connection, actor, "PAYMENT_WRITE", dealer, true);
                    FinancialAllocations.lock(connection, dealer);
                    var original =
                            Sql.one(
                                    connection,
                                    "SELECT * FROM mvp_payments WHERE id=? FOR UPDATE",
                                    payment);
                    var command =
                            MvpCommands.begin(
                                    connection,
                                    actor,
                                    "PAYMENT_REVERSE",
                                    requestKey,
                                    payment + ":" + version + ":" + why);
                    if (command.replayId() != null) {
                        return command.replayId();
                    }
                    MvpCommands.version(original, version);
                    if (!Sql.query(
                                    connection,
                                    "SELECT id FROM mvp_payment_reversals WHERE payment_id=?",
                                    payment)
                            .isEmpty()) {
                        throw new IllegalArgumentException("Phiếu thu đã được đảo toàn bộ.");
                    }
                    var now = clock.instant();
                    String number = MvpCommands.nextNumber(connection, "DT", MvpCommands.date(now));
                    long reversal =
                            Sql.insert(
                                    connection,
                                    "INSERT INTO"
                                        + " mvp_payment_reversals(payment_id,reason,recorded_by,recorded_at,number)"
                                        + " VALUES(?,?,?,?,?)",
                                    payment,
                                    why,
                                    actor,
                                    MvpCommands.timestamp(now),
                                    number);
                    Sql.update(
                            connection,
                            "UPDATE mvp_payments SET version=version+1 WHERE id=?",
                            payment);
                    FinancialAllocations.reallocate(
                            connection, dealer, "PAYMENT_REVERSE", reversal, now);
                    var after =
                            new LinkedHashMap<>(
                                    Sql.one(
                                            connection,
                                            "SELECT * FROM mvp_payment_reversals WHERE id=?",
                                            reversal));
                    after.put("original_amount", original.get("amount"));
                    AuditService.record(
                            connection,
                            actor,
                            "PAYMENT_REVERSED",
                            "PAYMENT",
                            payment,
                            original,
                            after);
                    return MvpCommands.finish(connection, command, reversal);
                });
    }

    // Tìm danh sách phiếu thu theo bộ lọc.
    public List<Map<String, Object>> payments(long actor) {
        return Sql.snapshot(
                source,
                connection -> {
                    var access = MvpAccess.internal(connection, actor, "INVOICE_READ");
                    return Sql.query(
                            connection,
                            "SELECT p.*,d.code dealer_code,d.name dealer_name,r.id"
                                + " reversal_id,u.full_name collector_name FROM mvp_payments p JOIN"
                                + " users u ON u.id=p.recorded_by JOIN dealers d ON"
                                + " d.id=p.dealer_id LEFT JOIN mvp_payment_reversals r ON"
                                + " r.payment_id=p.id WHERE (? OR d.primary_staff_id=?) ORDER BY"
                                + " p.paid_date DESC,p.id DESC LIMIT 100",
                            MvpAccess.allDealers(access),
                            actor);
                });
    }

    // Lấy thông tin chi tiết của phiếu thu.
    public Map<String, Object> payment(long actor, long id) {
        return Sql.snapshot(
                source,
                connection -> {
                    MvpAccess.internal(connection, actor, "INVOICE_READ");
                    var row =
                            new LinkedHashMap<>(
                                    Sql.one(
                                            connection,
                                            "SELECT p.*,d.code dealer_code,d.name"
                                                    + " dealer_name,u.full_name collector_name FROM"
                                                    + " mvp_payments p JOIN users u ON"
                                                    + " u.id=p.recorded_by JOIN dealers d ON"
                                                    + " d.id=p.dealer_id WHERE p.id=?",
                                            id));
                    MvpAccess.dealer(
                            connection, actor, "INVOICE_READ", Sql.id(row.get("dealer_id")), false);
                    var reversal =
                            Sql.query(
                                    connection,
                                    "SELECT * FROM mvp_payment_reversals WHERE payment_id=?",
                                    id);
                    if (!reversal.isEmpty()) {
                        row.put("reversal", reversal.get(0));
                    }
                    row.put(
                            "allocations",
                            Sql.query(
                                    connection,
                                    "SELECT a.*,i.number invoice_number FROM"
                                        + " mvp_payment_allocations a JOIN mvp_invoices i ON"
                                        + " i.id=a.invoice_id WHERE a.payment_id=? ORDER BY a.id",
                                    id));
                    return row;
                });
    }
}
