package db.migration;

import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

public final class V016__Vietnam_datetime extends BaseJavaMigration {
    @Override
    public boolean canExecuteInTransaction() { return false; }

    @Override
    public Integer getChecksum() { return 1601; }

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS application_time_zone (id INT PRIMARY KEY, zone_id VARCHAR(64) NOT NULL)");
        }
        boolean autoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try (Statement statement = connection.createStatement()) {
            try (var existing = statement.executeQuery("SELECT zone_id FROM application_time_zone WHERE id=1 FOR UPDATE")) {
                if (existing.next()) {
                    if (!"Asia/Ho_Chi_Minh".equals(existing.getString(1))) throw new IllegalStateException("Unsupported stored time zone");
                    connection.commit();
                    return;
                }
            }
            // DATETIME has no zone. TIMESTAMP already preserves instants and must not be shifted.
            var columns = new LinkedHashMap<String, List<String>>();
            try (var result = statement.executeQuery("SELECT TABLE_NAME,COLUMN_NAME FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND DATA_TYPE='datetime' ORDER BY TABLE_NAME,ORDINAL_POSITION")) {
                while (result.next()) columns.computeIfAbsent(result.getString(1), key -> new ArrayList<>()).add(result.getString(2));
            }
            for (var table : columns.entrySet()) {
                var assignments = new ArrayList<String>();
                for (String column : table.getValue()) {
                    String identifier = quote(column);
                    assignments.add(identifier + "=DATE_ADD(" + identifier + ",INTERVAL 7 HOUR)");
                }
                // Explicitly include updated_at so ON UPDATE cannot overwrite the historical value.
                statement.executeUpdate("UPDATE " + quote(table.getKey()) + " SET " + String.join(",", assignments));
            }
            statement.executeUpdate("INSERT INTO application_time_zone(id,zone_id) VALUES(1,'Asia/Ho_Chi_Minh')");
            connection.commit();
        } catch (Exception exception) {
            connection.rollback();
            throw exception;
        } finally {
            connection.setAutoCommit(autoCommit);
        }
    }

    private static String quote(String identifier) { return "`" + identifier.replace("`", "``") + "`"; }
}
