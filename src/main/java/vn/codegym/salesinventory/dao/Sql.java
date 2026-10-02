package vn.codegym.salesinventory.dao;

import java.sql.*;
import java.util.*;
import javax.sql.DataSource;

/** JDBC nhỏ gọn: tham số luôn bind, giao dịch dùng chung một connection. */
public final class Sql {
    private Sql() { }
    public interface Work<T> { T run(Connection connection) throws Exception; }
    public static <T> T transaction(DataSource source, Work<T> work) {
        try (Connection c = source.getConnection()) {
            c.setAutoCommit(false);
            try { T value = work.run(c); c.commit(); return value; }
            catch (Exception e) { c.rollback(); if (e instanceof RuntimeException r) throw r; throw new IllegalStateException("Không thể lưu dữ liệu.", e); }
        } catch (SQLException e) { throw new IllegalStateException("Không kết nối được cơ sở dữ liệu.", e); }
    }
    public static List<Map<String,Object>> query(Connection c, String sql, Object... args) throws SQLException {
        try (PreparedStatement s = c.prepareStatement(sql)) {
            bind(s,args);
            try (ResultSet rs = s.executeQuery()) {
                List<Map<String,Object>> rows = new ArrayList<>();
                ResultSetMetaData md = rs.getMetaData();
                while(rs.next()) { Map<String,Object> row = new LinkedHashMap<>();
                    for(int i=1;i<=md.getColumnCount();i++) row.put(md.getColumnLabel(i).toLowerCase(Locale.ROOT),rs.getObject(i));
                    rows.add(row);
                } return rows;
            }
        }
    }
    public static int update(Connection c,String sql,Object... args) throws SQLException {
        try(PreparedStatement s=c.prepareStatement(sql)) { bind(s,args); return s.executeUpdate(); }
    }
    public static long insert(Connection c,String sql,Object... args) throws SQLException {
        try(PreparedStatement s=c.prepareStatement(sql,Statement.RETURN_GENERATED_KEYS)) {
            bind(s,args); s.executeUpdate(); try(ResultSet rs=s.getGeneratedKeys()) { if(!rs.next()) throw new SQLException("Thiếu mã bản ghi."); return rs.getLong(1); }
        }
    }
    public static Map<String,Object> one(Connection c,String sql,Object... args) throws SQLException {
        List<Map<String,Object>> rows=query(c,sql,args); if(rows.isEmpty()) throw new IllegalArgumentException("Không tìm thấy bản ghi."); return rows.get(0);
    }
    public static long id(Object value) { return ((Number)value).longValue(); }
    public static String text(Object value) { return value==null ? "" : value.toString(); }
    public static java.time.Instant instant(Object value) {
        if(value instanceof Timestamp t) return t.toInstant();
        if(value instanceof java.time.LocalDateTime t) return t.atZone(vn.codegym.salesinventory.config.VietnamTime.ZONE).toInstant();
        throw new IllegalArgumentException("Thời điểm không hợp lệ.");
    }
    private static void bind(PreparedStatement s,Object[] args) throws SQLException {
        for(int i=0;i<args.length;i++) s.setObject(i+1,args[i]);
    }
}
