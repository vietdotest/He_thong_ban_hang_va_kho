package vn.codegym.salesinventory.service;

import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.slf4j.LoggerFactory;
import vn.codegym.salesinventory.dao.Sql;

/**
 * S2-01, S2-08: Xử lý từng dòng nhập trong giao dịch riêng; khóa dòng để tránh chạy trùng.
 */
public final class ImportWorker implements AutoCloseable {

    private static final int LEASE_SECONDS = 60;

    private final DataSource source;

    private final UserManagementService users;

    private final Clock clock;

    private final Duration reportAge;

    private final int threads;

    private final ConcurrentMap<String, String> active = new ConcurrentHashMap<>();

    private final AtomicBoolean closing = new AtomicBoolean();

    private ExecutorService executors;

    private ScheduledExecutorService maintenance;

    // Chứa thông tin công việc nhập mà tác vụ nền đang xử lý.
    private record Job(String id, String token, long actor, String kind, boolean cost) {}

    // Chứa một dòng nhập cùng thông tin nhận quyền xử lý.
    private record Row(int number, String token) {}

    // Báo tác vụ không còn giữ quyền xử lý công việc hoặc dòng nhập.
    private static final class LostClaim extends RuntimeException {}

    public ImportWorker(DataSource source, UserManagementService users, int threads, int poolSize) {
        this(source, users, threads, poolSize, Clock.systemUTC(), Duration.ofDays(7));
    }

    public ImportWorker(
            DataSource source,
            UserManagementService users,
            int threads,
            int poolSize,
            Clock clock,
            Duration reportAge) {
        if (threads < 1 || poolSize < 3 || reportAge.isNegative() || reportAge.isZero()) {
            throw new IllegalArgumentException("Cấu hình worker không hợp lệ.");
        }
        this.source = source;
        this.users = users;
        this.threads = Math.min(threads, poolSize - 2);
        this.clock = clock;
        this.reportAge = reportAge;
    }

    // Khởi chạy tác vụ nền theo lịch.
    public synchronized void start() {
        if (executors != null || closing.get()) {
            throw new IllegalStateException("Worker đã khởi động hoặc đóng.");
        }
        executors =
                Executors.newFixedThreadPool(
                        threads,
                        r -> {
                            var thread = new Thread(r, "import-worker");
                            thread.setDaemon(true);
                            return thread;
                        });
        maintenance =
                Executors.newSingleThreadScheduledExecutor(
                        r -> {
                            var t = new Thread(r, "import-heartbeat");
                            t.setDaemon(true);
                            return t;
                        });
        maintenance.scheduleWithFixedDelay(this::maintain, 1, 5, TimeUnit.SECONDS);
        for (int i = 0; i < threads; i++) {
            executors.submit(
                    () -> {
                        while (!closing.get()) {
                            try {
                                if (!runOnce()) {
                                    Thread.sleep(750);
                                }
                            } catch (InterruptedException exception) {
                                Thread.currentThread().interrupt();
                                return;
                            } catch (RuntimeException e) {
                                safeWarning();
                                try {
                                    Thread.sleep(1000);
                                } catch (InterruptedException stopped) {
                                    Thread.currentThread().interrupt();
                                    return;
                                }
                            }
                        }
                    });
        }
    }

    private static void safeWarning() {
        LoggerFactory.getLogger(ImportWorker.class)
                .warn(
                        "Tác vụ nhập tạm gián đoạn; giữ trạng thái bền vững để phục hồi, không tự"
                                + " gửi lại email chưa rõ kết quả.");
    }

    // Cập nhật tiến độ và trạng thái nhận việc của tác vụ nhập.
    private void maintain() {
        try {
            for (var entry : active.entrySet()) {
                Sql.transaction(
                        source,
                        connection -> {
                            Sql.update(
                                    connection,
                                    "UPDATE import_jobs SET"
                                        + " lease_until=TIMESTAMPADD(SECOND,?,CURRENT_TIMESTAMP(6)),heartbeat_at=CURRENT_TIMESTAMP(6)"
                                        + " WHERE id=? AND lease_owner=? AND status='RUNNING'",
                                    LEASE_SECONDS,
                                    entry.getValue(),
                                    entry.getKey());
                            return null;
                        });
            }
            new ImportJobService(source, clock, Duration.ofMinutes(30), reportAge).cleanup();
        } catch (RuntimeException exception) {
            safeWarning();
        }
    }

    // Xử lý một lượt các công việc nhập đang chờ.
    public boolean runOnce() {
        if (closing.get()) {
            return false;
        }
        Job job = claim();
        if (job == null) {
            return false;
        }
        active.put(job.token, job.id);
        try {
            while (!closing.get()) {
                Row row = claimRow(job);
                if (row == null) {
                    finish(job, "COMPLETED", "");
                    break;
                }
                if (!process(job, row)) {
                    break;
                }
            }
            if (closing.get()) {
                release(job);
            }
            return true;
        } finally {
            active.remove(job.token);
        }
    }

    // Nhận công việc đang chờ để xử lý.
    private Job claim() {
        return Sql.transaction(
                source,
                connection -> {
                    var found =
                            Sql.query(
                                    connection,
                                    "SELECT id,actor_id,kind,has_cost FROM import_jobs WHERE"
                                            + " status='QUEUED' OR (status='RUNNING' AND"
                                            + " lease_until<=CURRENT_TIMESTAMP(6)) ORDER BY"
                                            + " created_at,id LIMIT 1 FOR UPDATE SKIP LOCKED");
                    if (found.isEmpty()) {
                        return null;
                    }
                    var data = found.get(0);
                    String id = Sql.text(data.get("id")), token = UUID.randomUUID().toString();
                    // Chờ giao dịch cũ kết thúc; dòng đã commit thành công sẽ không bị nhận lại.
                    Sql.update(
                            connection,
                            "UPDATE import_job_rows SET state='READY',row_token=NULL WHERE job_id=?"
                                    + " AND state='CLAIMED'",
                            id);
                    Sql.update(
                            connection,
                            "UPDATE import_job_rows SET state='REVIEW',row_token=NULL,message='Tiến"
                                + " trình dừng trong lần thử gửi email; cần kiểm tra, không tự gửi"
                                + " lại.' WHERE job_id=? AND state='EMAIL_ATTEMPT'",
                            id);
                    Sql.update(
                            connection,
                            "UPDATE import_mail_attempts a JOIN import_job_rows r ON"
                                    + " r.mail_attempt_id=a.id SET"
                                    + " a.state='REVIEW',a.finished_at=CURRENT_TIMESTAMP(6) WHERE"
                                    + " r.job_id=? AND r.state='REVIEW' AND a.state='STARTED'",
                            id);
                    Sql.update(
                            connection,
                            "UPDATE import_jobs SET"
                                + " status='RUNNING',lease_owner=?,lease_until=TIMESTAMPADD(SECOND,?,CURRENT_TIMESTAMP(6)),heartbeat_at=CURRENT_TIMESTAMP(6)"
                                + " WHERE id=?",
                            token,
                            LEASE_SECONDS,
                            id);
                    return new Job(
                            id,
                            token,
                            Sql.id(data.get("actor_id")),
                            Sql.text(data.get("kind")),
                            DealerAddressService.isDefault(data.get("has_cost")));
                });
    }

    private boolean owns(Connection connection, Job job) throws SQLException {
        return !Sql.query(
                        connection,
                        "SELECT id FROM import_jobs WHERE id=? AND lease_owner=? AND"
                                + " status='RUNNING' AND lease_until>CURRENT_TIMESTAMP(6)",
                        job.id,
                        job.token)
                .isEmpty();
    }

    // Khóa và nhận quyền xử lý một dòng nhập, tránh hai tác vụ chạy trùng.
    private Row claimRow(Job job) {
        return Sql.transaction(
                source,
                connection -> {
                    var held =
                            Sql.query(
                                    connection,
                                    "SELECT id FROM import_jobs WHERE id=? AND lease_owner=? AND"
                                        + " status='RUNNING' AND lease_until>CURRENT_TIMESTAMP(6)"
                                        + " FOR UPDATE",
                                    job.id,
                                    job.token);
                    if (held.isEmpty()) {
                        throw new LostClaim();
                    }
                    // Gia hạn trước mỗi dòng vì chế độ phục hồi hoặc dòng lệnh có thể không chạy
                    // tác vụ nhịp nền.
                    Sql.update(
                            connection,
                            "UPDATE import_jobs SET"
                                + " lease_until=TIMESTAMPADD(SECOND,?,CURRENT_TIMESTAMP(6)),heartbeat_at=CURRENT_TIMESTAMP(6)"
                                + " WHERE id=? AND lease_owner=?",
                            LEASE_SECONDS,
                            job.id,
                            job.token);
                    var found =
                            Sql.query(
                                    connection,
                                    "SELECT source_row FROM import_job_rows WHERE job_id=? AND"
                                        + " state='READY' ORDER BY source_row LIMIT 1 FOR UPDATE",
                                    job.id);
                    if (found.isEmpty()) {
                        return null;
                    }
                    int number = ((Number) found.get(0).get("source_row")).intValue();
                    String token = UUID.randomUUID().toString();
                    Sql.update(
                            connection,
                            "UPDATE import_job_rows SET state='CLAIMED',row_token=? WHERE job_id=?"
                                    + " AND source_row=?",
                            token,
                            job.id,
                            number);
                    return new Row(number, token);
                });
    }

    // Khóa dòng nhập và kiểm tra tác vụ còn giữ quyền xử lý.
    private Map<String, Object> lockedRow(Connection connection, Job job, Row row, String state)
            throws SQLException {
        var found =
                Sql.query(
                        connection,
                        "SELECT * FROM import_job_rows WHERE job_id=? AND source_row=? AND"
                                + " row_token=? AND state=? FOR UPDATE",
                        job.id,
                        row.number,
                        row.token,
                        state);
        if (found.isEmpty() || !owns(connection, job)) {
            throw new LostClaim();
        }
        return found.get(0);
    }

    // Xử lý một công việc nhập với trạng thái đã lưu.
    private boolean process(Job job, Row row) {
        AtomicBoolean attempted = new AtomicBoolean();
        try {
            if (job.kind.equals("USER")) {
                Sql.transaction(
                        source,
                        connection -> {
                            var data = lockedRow(connection, job, row, "CLAIMED");
                            ImportJobService.access(connection, job.actor, job.kind);
                            var input =
                                    UserImportService.resolve(
                                            connection,
                                            ImportJobService.cells(data, false, "USER"));
                            String attempt = UUID.randomUUID().toString();
                            try {
                                Sql.update(
                                        connection,
                                        "INSERT INTO"
                                            + " import_mail_attempts(id,job_id,source_row,actor_id,username_normalized)"
                                            + " VALUES(?,?,?,?,?)",
                                        attempt,
                                        job.id,
                                        row.number,
                                        job.actor,
                                        input.command().normalizedUsername());
                            } catch (SQLException exception) {
                                if (exception.getErrorCode() == 1062) {
                                    throw new IllegalArgumentException(
                                            "Có lần gửi trước chưa rõ kết quả cho tên đăng nhập"
                                                + " này. Cần kiểm tra email trước, không gửi lại.");
                                }
                                throw exception;
                            }
                            // Lưu dấu gửi trước SMTP ở giao dịch riêng; nếu tiến trình dừng thì
                            // chưa chắc thư đã gửi.
                            Sql.update(
                                    connection,
                                    "UPDATE import_job_rows SET"
                                        + " state='EMAIL_ATTEMPT',mail_attempt_id=?,mail_attempt_at=CURRENT_TIMESTAMP(6)"
                                        + " WHERE job_id=? AND source_row=? AND row_token=?",
                                    attempt,
                                    job.id,
                                    row.number,
                                    row.token);
                            return null;
                        });
            }
            Sql.transaction(
                    source,
                    c -> {
                        var data =
                                lockedRow(
                                        c,
                                        job,
                                        row,
                                        job.kind.equals("USER") ? "EMAIL_ATTEMPT" : "CLAIMED");
                        var access = ImportJobService.access(c, job.actor, job.kind);
                        if (job.cost) {
                            access.require("COST_WRITE");
                        }
                        var cells = ImportJobService.cells(data, job.cost, job.kind);
                        long id;
                        if (job.kind.equals("USER")) {
                            var input = UserImportService.resolve(c, cells);
                            id =
                                    users.createImport(
                                            c,
                                            input.command(),
                                            job.actor,
                                            input.roles(),
                                            input.warehouses(),
                                            input.territories(),
                                            () -> attempted.set(true));
                        } else {
                            Sql.one(
                                    c,
                                    "SELECT name FROM catalog_locks WHERE name='CATEGORY_TREE' FOR"
                                            + " UPDATE");
                            var category =
                                    Sql.query(
                                            c,
                                            "SELECT id FROM categories WHERE code=?",
                                            ProductImportService.cell(cells, 2));
                            if (category.isEmpty()) {
                                throw new IllegalArgumentException("Mã nhóm không còn tồn tại.");
                            }
                            var found =
                                    Sql.query(
                                            c,
                                            "SELECT id,version FROM products WHERE sku=? FOR"
                                                    + " UPDATE",
                                            ProductImportService.cell(cells, 0));
                            id = found.isEmpty() ? 0 : Sql.id(found.get(0).get("id"));
                            long version =
                                    found.isEmpty() ? 0 : Sql.id(found.get(0).get("version"));
                            if (id != Sql.id(data.get("expected_id"))
                                    || version != Sql.id(data.get("expected_version"))) {
                                throw new IllegalArgumentException(
                                        "SKU đã thay đổi sau xem trước. Hãy tải lại tệp.");
                            }
                            id =
                                    ProductService.save(
                                            c,
                                            access,
                                            job.actor,
                                            id,
                                            ProductImportService.input(
                                                    cells,
                                                    Sql.id(category.get(0).get("id")),
                                                    version));
                        }
                        // Dữ liệu, nhật ký và trạng thái thành công được commit cùng một giao dịch.
                        if (job.kind.equals("USER")) {
                            Sql.update(
                                    c,
                                    "UPDATE import_mail_attempts SET"
                                            + " state='SUCCEEDED',finished_at=? WHERE id=? AND"
                                            + " state='STARTED'",
                                    Timestamp.from(clock.instant()),
                                    data.get("mail_attempt_id"));
                        }
                        Sql.update(
                                c,
                                "UPDATE import_job_rows SET"
                                    + " state='SUCCESS',entity_id=?,committed_at=?,row_token=NULL,message=''"
                                    + " WHERE job_id=? AND source_row=? AND row_token=?",
                                id,
                                Timestamp.from(clock.instant()),
                                job.id,
                                row.number,
                                row.token);
                        return null;
                    });
            return true;
        } catch (LostClaim e) {
            return false;
        } catch (SecurityException e) {
            result(
                    job,
                    row,
                    "SKIPPED",
                    "Người nhập bị khóa hoặc không còn quyền; dòng này chưa được xử lý.");
            finish(
                    job,
                    "STOPPED",
                    "Người nhập bị khóa hoặc không còn quyền. Kết quả đã hoàn tất được giữ"
                            + " nguyên.");
            return false;
        } catch (RuntimeException e) {
            boolean review =
                    job.kind.equals("USER")
                            && attempted.get()
                            && !MailDeliveryFailure.isDefinitive(e);
            String message =
                    review
                            ? "Chưa rõ kết quả gửi email hoặc commit. Cần kiểm tra email; không tự"
                                    + " gửi lại."
                            : MailDeliveryFailure.isDefinitive(e)
                                    ? "SMTP từ chối gửi email; dữ liệu tài khoản đã rollback."
                                    : e instanceof IllegalArgumentException
                                            ? ImportJobService.limited(e.getMessage(), 1000)
                                            : "Không thể lưu dòng; giao dịch đã rollback. Hãy kiểm"
                                                    + " tra dữ liệu hoặc liên hệ quản trị.";
            result(job, row, review ? "REVIEW" : "FAILED", message);
            return true;
        }
    }

    // Lưu trạng thái và thông báo của dòng nhập khi xử lý thất bại hoặc cần kiểm tra lại.
    private void result(Job job, Row row, String state, String message) {
        Sql.transaction(
                source,
                connection -> {
                    // Chỉ cập nhật dòng còn giữ đúng token; tránh ghi đè tác vụ khác hoặc dòng đã
                    // commit.
                    var held =
                            Sql.query(
                                    connection,
                                    "SELECT mail_attempt_id FROM import_job_rows WHERE job_id=? AND"
                                            + " source_row=? AND row_token=? AND state IN"
                                            + " ('CLAIMED','EMAIL_ATTEMPT') FOR UPDATE",
                                    job.id,
                                    row.number,
                                    row.token);
                    if (held.isEmpty()) {
                        return null;
                    }
                    Sql.update(
                            connection,
                            "UPDATE import_job_rows SET state=?,message=?,row_token=NULL WHERE"
                                    + " job_id=? AND source_row=? AND row_token=? AND state IN"
                                    + " ('CLAIMED','EMAIL_ATTEMPT')",
                            state,
                            message,
                            job.id,
                            row.number,
                            row.token);
                    if (job.kind.equals("USER") && held.get(0).get("mail_attempt_id") != null) {
                        Sql.update(
                                connection,
                                "UPDATE import_mail_attempts SET"
                                        + " state=?,finished_at=CURRENT_TIMESTAMP(6) WHERE id=? AND"
                                        + " state='STARTED'",
                                state.equals("REVIEW") ? "REVIEW" : "FAILED",
                                held.get(0).get("mail_attempt_id"));
                    }
                    return null;
                });
    }

    // Kết thúc công việc nhập với trạng thái và lý do đã xác định.
    private void finish(Job job, String status, String reason) {
        Sql.transaction(
                source,
                connection -> {
                    var held =
                            Sql.query(
                                    connection,
                                    "SELECT id FROM import_jobs WHERE id=? AND lease_owner=? AND"
                                            + " status='RUNNING' FOR UPDATE",
                                    job.id,
                                    job.token);
                    if (held.isEmpty()) {
                        return null;
                    }
                    if (status.equals("STOPPED")) {
                        Sql.update(
                                connection,
                                "UPDATE import_job_rows SET state='SKIPPED',message='Chưa xử lý do"
                                    + " người nhập không còn quyền.',row_token=NULL WHERE job_id=?"
                                    + " AND state IN ('READY','CLAIMED')",
                                job.id);
                    }
                    Sql.update(
                            connection,
                            "UPDATE import_jobs SET"
                                + " status=?,reason=?,finished_at=?,report_expires_at=?,lease_owner=NULL,lease_until=NULL"
                                + " WHERE id=? AND lease_owner=?",
                            status,
                            reason,
                            Timestamp.from(clock.instant()),
                            Timestamp.from(clock.instant().plus(reportAge)),
                            job.id,
                            job.token);
                    return null;
                });
    }

    // Giải phóng quyền xử lý công việc sau khi hoàn tất lượt chạy.
    private void release(Job job) {
        Sql.transaction(
                source,
                connection -> {
                    Sql.update(
                            connection,
                            "UPDATE import_jobs SET lease_until=CURRENT_TIMESTAMP(6) WHERE id=? AND"
                                    + " lease_owner=? AND status='RUNNING'",
                            job.id,
                            job.token);
                    return null;
                });
    }

    // Dừng tác vụ và đóng tài nguyên đang sử dụng.
    @Override
    public synchronized void close() {
        closing.set(true);
        if (executors == null) {
            return;
        }
        executors.shutdown();
        try {
            if (!executors.awaitTermination(10, TimeUnit.SECONDS)) {
                executors.shutdownNow();
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            executors.shutdownNow();
        }
        maintenance.shutdownNow();
    }
}
