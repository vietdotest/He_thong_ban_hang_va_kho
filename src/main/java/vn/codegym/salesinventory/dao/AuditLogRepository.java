package vn.codegym.salesinventory.dao;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;

/**
 * S2-04: Quy định các thao tác đọc, ghi nhật ký thao tác trên kết nối do service cung cấp.
 */
public interface AuditLogRepository {

    // Ghi sự kiện thao tác cùng người thực hiện, thời điểm và thông tin yêu cầu.
    void record(
            Connection connection,
            Long actorUserId,
            String eventType,
            String details,
            String ipAddress,
            String userAgent,
            Instant occurredAt)
            throws SQLException;
}
