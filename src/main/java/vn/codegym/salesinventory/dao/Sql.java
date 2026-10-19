package vn.codegym.salesinventory.dao;

import java.sql.*;
import java.util.*;
import javax.sql.DataSource;

/**
 * Dùng chung: Hỗ trợ truy vấn có tham số và commit, rollback giao dịch trên một kết nối.
 */
public final class Sql {

    private Sql() {}

    // Quy định công việc được thực hiện trên một kết nối cơ sở dữ liệu.
    public interface Work<T> {

        // Thực hiện công việc trên kết nối do bên gọi quản lý.
        T run(Connection connection) throws Exception;
    }

    // Thực hiện công việc trên một kết nối, commit khi thành công và rollback khi lỗi.
    public static <T> T transaction(DataSource source, Work<T> work) {
        try (Connection connection = source.getConnection()) {
            connection.setAutoCommit(false);
            try {
                T value = work.run(connection);
                connection.commit();
                return value;
            } catch (Exception exception) {
                connection.rollback();
                if (exception instanceof RuntimeException r) {
                    throw r;
                }
                throw new IllegalStateException("Không thể lưu dữ liệu.", exception);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Không kết nối được cơ sở dữ liệu.", e);
        }
    }

    // Đọc tổng số, trang dữ liệu và tiến độ trên cùng một ảnh chụp dữ liệu.
    public static <T> T snapshot(DataSource source, Work<T> work) {
        try (Connection connection = source.getConnection()) {
            connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            connection.setAutoCommit(false);
            try {
                T value = work.run(connection);
                connection.commit();
                return value;
            } catch (Exception exception) {
                connection.rollback();
                if (exception instanceof RuntimeException r) {
                    throw r;
                }
                throw new IllegalStateException("Không thể đọc dữ liệu.", exception);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Không kết nối được cơ sở dữ liệu.", e);
        }
    }

    // Chạy truy vấn có tham số và trả các dòng kết quả.
    public static List<Map<String, Object>> query(
            Connection connection, String sql, Object... parameters) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, parameters);
            try (ResultSet resultSet = statement.executeQuery()) {
                List<Map<String, Object>> rows = new ArrayList<>();
                ResultSetMetaData metadata = resultSet.getMetaData();
                while (resultSet.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int i = 1; i <= metadata.getColumnCount(); i++) {
                        row.put(
                                metadata.getColumnLabel(i).toLowerCase(Locale.ROOT),
                                resultSet.getObject(i));
                    }
                    rows.add(row);
                }
                return rows;
            }
        }
    }

    // Chạy câu lệnh cập nhật có tham số và trả số dòng bị ảnh hưởng.
    public static int update(Connection connection, String sql, Object... parameters)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, parameters);
            return statement.executeUpdate();
        }
    }

    // Thêm bản ghi bằng truy vấn có tham số và trả mã vừa tạo.
    public static long insert(Connection connection, String sql, Object... parameters)
            throws SQLException {
        try (PreparedStatement statement =
                connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            bind(statement, parameters);
            statement.executeUpdate();
            try (ResultSet resultSet = statement.getGeneratedKeys()) {
                if (!resultSet.next()) {
                    throw new SQLException("Thiếu mã bản ghi.");
                }
                return resultSet.getLong(1);
            }
        }
    }

    // Lấy một dòng kết quả hoặc báo không tìm thấy bản ghi.
    public static Map<String, Object> one(Connection connection, String sql, Object... parameters)
            throws SQLException {
        List<Map<String, Object>> rows = query(connection, sql, parameters);
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("Không tìm thấy bản ghi.");
        }
        return rows.get(0);
    }

    public static long id(Object value) {
        return ((Number) value).longValue();
    }

    public static String text(Object value) {
        return value == null ? "" : value.toString();
    }

    // Chuyển thời gian từ cơ sở dữ liệu về thời điểm thống nhất của ứng dụng.
    public static java.time.Instant instant(Object value) {
        if (value instanceof Timestamp t) {
            return t.toInstant();
        }
        if (value instanceof java.time.LocalDateTime t) {
            return t.atZone(vn.codegym.salesinventory.config.VietnamTime.ZONE).toInstant();
        }
        throw new IllegalArgumentException("Thời điểm không hợp lệ.");
    }

    private static void bind(PreparedStatement statement, Object[] parameters) throws SQLException {
        for (int i = 0; i < parameters.length; i++) {
            statement.setObject(i + 1, parameters[i]);
        }
    }
}
