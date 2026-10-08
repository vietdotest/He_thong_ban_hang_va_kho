package vn.codegym.salesinventory.dao;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import vn.codegym.salesinventory.dto.UserAccountCommand;
import vn.codegym.salesinventory.dto.UserSearchCriteria;
import vn.codegym.salesinventory.model.ManagedUser;
import vn.codegym.salesinventory.model.RoleOption;
import vn.codegym.salesinventory.model.UserStatus;

public final class JdbcUserManagementRepository implements UserManagementRepository {
    private static final String SCOPES = """
            ,CONCAT_WS(' · ',
              (SELECT GROUP_CONCAT(CONCAT('Kho: ',w.name) ORDER BY w.name SEPARATOR ', ') FROM user_warehouses uw JOIN warehouses w ON w.id=uw.warehouse_id WHERE uw.user_id=u.id),
              (SELECT GROUP_CONCAT(CONCAT('Địa bàn: ',t.name) ORDER BY t.name SEPARATOR ', ') FROM user_territories ut JOIN territories t ON t.id=ut.territory_id WHERE ut.user_id=u.id)) AS scope_summary
            """;
    private static final String FILTER = """
            WHERE (? = ''
                   OR LOWER(u.full_name) LIKE ?
                   OR u.username_normalized LIKE ?
                   OR COALESCE(u.phone_normalized, '') LIKE ?)
              AND (? = '' OR EXISTS (
                    SELECT 1 FROM user_roles fur
                    JOIN roles fr ON fr.id = fur.role_id
                    WHERE fur.user_id = u.id AND fr.code = ?))
              AND (? = '' OR u.status = ?)
            """;

    @Override
    public boolean userHasRole(Connection connection, long userId, String roleCode) throws SQLException {
        String sql = """
                SELECT 1
                FROM user_roles ur
                JOIN roles r ON r.id = ur.role_id
                WHERE ur.user_id = ? AND r.code = ?
                LIMIT 1
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, userId);
            statement.setString(2, roleCode);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        }
    }

    @Override
    public boolean roleExists(Connection connection, String roleCode) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT 1 FROM roles WHERE code = ? LIMIT 1")) {
            statement.setString(1, roleCode);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        }
    }

    @Override
    public List<RoleOption> findRoles(Connection connection) throws SQLException {
        List<RoleOption> roles = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id, code, name FROM roles ORDER BY name, code");
             ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                roles.add(new RoleOption(
                        resultSet.getLong("id"),
                        resultSet.getString("code"),
                        resultSet.getString("name")
                ));
            }
        }
        return roles;
    }

    @Override
    public List<ManagedUser> search(Connection connection, UserSearchCriteria criteria) throws SQLException {
        String sql = """
                SELECT u.id, u.username, u.email, u.full_name, u.phone, u.status,
                       u.must_change_password, u.version, u.created_at,
                       (SELECT r.code FROM user_roles ur JOIN roles r ON r.id = ur.role_id
                        WHERE ur.user_id = u.id ORDER BY r.code LIMIT 1) AS role_code,
                       (SELECT GROUP_CONCAT(r.name ORDER BY r.code SEPARATOR ', ') FROM user_roles ur JOIN roles r ON r.id = ur.role_id
                        WHERE ur.user_id = u.id) AS role_name
                """ + SCOPES + " FROM users u " + FILTER + """
                ORDER BY u.full_name, u.id
                LIMIT ? OFFSET ?
                """;
        List<ManagedUser> users = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            int next = bindFilters(statement, criteria);
            statement.setInt(next++, criteria.pageSize());
            statement.setLong(next, criteria.offset());
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    users.add(map(resultSet));
                }
            }
        }
        return users;
    }

    @Override
    public long count(Connection connection, UserSearchCriteria criteria) throws SQLException {
        String sql = "SELECT COUNT(*) FROM users u " + FILTER;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bindFilters(statement, criteria);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getLong(1);
            }
        }
    }

    @Override
    public Optional<ManagedUser> findByIdForUpdate(Connection connection, long userId) throws SQLException {
        String sql = """
                SELECT u.id, u.username, u.email, u.full_name, u.phone, u.status,
                       u.must_change_password, u.version, u.created_at,
                       r.code AS role_code, r.name AS role_name
                """ + SCOPES + """
                FROM users u
                LEFT JOIN user_roles ur ON ur.user_id = u.id
                LEFT JOIN roles r ON r.id = ur.role_id
                WHERE u.id = ?
                ORDER BY r.code
                LIMIT 1
                FOR UPDATE
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, userId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(map(resultSet)) : Optional.empty();
            }
        }
    }

    @Override
    public long create(Connection connection, UserAccountCommand command, String passwordHash, Instant createdAt)
            throws SQLException {
        String sql = """
                INSERT INTO users (
                    username, username_normalized, email, email_normalized,
                    full_name, phone, phone_normalized, password_hash,
                    must_change_password, status, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, TRUE, ?, ?, ?)
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, command.username());
            statement.setString(2, command.normalizedUsername());
            statement.setString(3, command.email());
            statement.setString(4, command.normalizedEmail());
            statement.setString(5, command.fullName());
            statement.setString(6, command.phone());
            statement.setString(7, command.normalizedPhone());
            statement.setString(8, passwordHash);
            statement.setString(9, command.status().name());
            statement.setTimestamp(10, Timestamp.from(createdAt));
            statement.setTimestamp(11, Timestamp.from(createdAt));
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (!keys.next()) {
                    throw new SQLException("Creating a user did not return an id");
                }
                return keys.getLong(1);
            }
        }
    }

    @Override
    public void replaceRole(Connection connection, long userId, String roleCode) throws SQLException {
        try (PreparedStatement delete = connection.prepareStatement("DELETE FROM user_roles WHERE user_id = ?")) {
            delete.setLong(1, userId);
            delete.executeUpdate();
        }
        String sql = """
                INSERT INTO user_roles (user_id, role_id)
                SELECT ?, id FROM roles WHERE code = ?
                """;
        try (PreparedStatement insert = connection.prepareStatement(sql)) {
            insert.setLong(1, userId);
            insert.setString(2, roleCode);
            if (insert.executeUpdate() != 1) {
                throw new SQLException("The selected role no longer exists");
            }
        }
    }

    @Override
    public int update(Connection connection, long userId, UserAccountCommand command) throws SQLException {
        String sql = """
                UPDATE users
                SET username = ?, username_normalized = ?, email = ?, email_normalized = ?,
                    full_name = ?, phone = ?, phone_normalized = ?, status = ?,
                    version = version + 1
                WHERE id = ? AND version = ?
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, command.username());
            statement.setString(2, command.normalizedUsername());
            statement.setString(3, command.email());
            statement.setString(4, command.normalizedEmail());
            statement.setString(5, command.fullName());
            statement.setString(6, command.phone());
            statement.setString(7, command.normalizedPhone());
            statement.setString(8, command.status().name());
            statement.setLong(9, userId);
            statement.setLong(10, command.version());
            return statement.executeUpdate();
        }
    }

    private static int bindFilters(PreparedStatement statement, UserSearchCriteria criteria) throws SQLException {
        String keyword = criteria.keyword().toLowerCase(Locale.ROOT);
        String pattern = "%" + keyword + "%";
        String phoneDigits = criteria.keyword().replaceAll("\\D", "");
        String phonePattern = phoneDigits.isBlank() ? "\u0000" : "%" + phoneDigits + "%";
        int index = 1;
        statement.setString(index++, keyword);
        statement.setString(index++, pattern);
        statement.setString(index++, pattern);
        statement.setString(index++, phonePattern);
        statement.setString(index++, criteria.roleCode());
        statement.setString(index++, criteria.roleCode());
        statement.setString(index++, criteria.status());
        statement.setString(index++, criteria.status());
        return index;
    }

    private static ManagedUser map(ResultSet resultSet) throws SQLException {
        return new ManagedUser(
                resultSet.getLong("id"),
                resultSet.getString("username"),
                resultSet.getString("email"),
                resultSet.getString("full_name"),
                resultSet.getString("phone"),
                UserStatus.valueOf(resultSet.getString("status")),
                resultSet.getString("role_code"),
                resultSet.getString("role_name"),
                resultSet.getBoolean("must_change_password"),
                resultSet.getLong("version"),
                resultSet.getTimestamp("created_at").toInstant(),
                resultSet.getString("scope_summary")
        );
    }
}
