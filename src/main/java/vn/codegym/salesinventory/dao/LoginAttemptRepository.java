package vn.codegym.salesinventory.dao;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;

/**
 * S1-01: Quy định các thao tác đọc, ghi các lần thử đăng nhập trên kết nối do service cung cấp.
 */
public interface LoginAttemptRepository {

    // Ghi một lần thử đăng nhập để theo dõi kết quả xác thực.
    void record(
            Connection connection,
            Long userId,
            String identityHash,
            String outcome,
            String ipAddress,
            String userAgent,
            Instant occurredAt)
            throws SQLException;
}
