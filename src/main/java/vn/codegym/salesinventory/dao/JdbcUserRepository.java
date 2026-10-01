package vn.codegym.salesinventory.dao;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import vn.codegym.salesinventory.model.User;
import vn.codegym.salesinventory.model.UserStatus;

public final class JdbcUserRepository implements UserRepository {
    private static final String FIND_FOR_UPDATE = """
            SELECT id, username, email, full_name, phone, password_hash, must_change_password, status,
                   failed_login_count, locked_until
            FROM users
            WHERE username_normalized = ? OR email_normalized = ?
            LIMIT 1
            FOR UPDATE
            """;
    private static final String FIND_BY_ID_FOR_UPDATE = """
            SELECT id, username, email, full_name, phone, password_hash, must_change_password, status,
                   failed_login_count, locked_until
            FROM users
            WHERE id = ?
            FOR UPDATE
            """;

    @Override
    public Optional<User> findByIdentityForUpdate(Connection connection, String normalizedIdentity) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(FIND_FOR_UPDATE)) {
            statement.setString(1, normalizedIdentity);
            statement.setString(2, normalizedIdentity);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(mapUser(resultSet)) : Optional.empty();
            }
        }
    }

    @Override
    public Optional<User> findByIdForUpdate(Connection connection, long userId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(FIND_BY_ID_FOR_UPDATE)) {
            statement.setLong(1, userId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(mapUser(resultSet)) : Optional.empty();
            }
        }
    }

    @Override
    public Set<String> findRoleCodes(Connection connection, long userId) throws SQLException {
        String sql = """
                SELECT r.code
                FROM user_roles ur
                JOIN roles r ON r.id = ur.role_id
                WHERE ur.user_id = ?
                ORDER BY r.code
                """;
        Set<String> roles = new LinkedHashSet<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, userId);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    roles.add(resultSet.getString("code"));
                }
            }
        }
        return roles;
    }

    @Override
    public void updateFailedLogin(Connection connection, long userId, int failureCount, Instant lockedUntil)
            throws SQLException {
        String sql = """
                UPDATE users
                SET failed_login_count = ?, locked_until = ?, version = version + 1
                WHERE id = ?
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, failureCount);
            if (lockedUntil == null) {
                statement.setNull(2, java.sql.Types.TIMESTAMP);
            } else {
                statement.setTimestamp(2, Timestamp.from(lockedUntil));
            }
            statement.setLong(3, userId);
            statement.executeUpdate();
        }
    }

    @Override
    public void recordSuccessfulLogin(Connection connection, long userId, Instant loginTime) throws SQLException {
        String sql = """
                UPDATE users
                SET failed_login_count = 0,
                    locked_until = NULL,
                    last_login_at = ?,
                    version = version + 1
                WHERE id = ?
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setTimestamp(1, Timestamp.from(loginTime));
            statement.setLong(2, userId);
            statement.executeUpdate();
        }
    }

    @Override
    public void updatePassword(Connection connection, long userId, String passwordHash) throws SQLException {
        String sql = """
                UPDATE users
                SET password_hash = ?, must_change_password = FALSE,
                    failed_login_count = 0, locked_until = NULL, version = version + 1
                WHERE id = ?
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, passwordHash);
            statement.setLong(2, userId);
            statement.executeUpdate();
        }
    }

    private static User mapUser(ResultSet resultSet) throws SQLException {
        Timestamp lockedUntil = resultSet.getTimestamp("locked_until");
        return new User(
                resultSet.getLong("id"),
                resultSet.getString("username"),
                resultSet.getString("email"),
                resultSet.getString("full_name"),
                resultSet.getString("phone"),
                resultSet.getString("password_hash"),
                resultSet.getBoolean("must_change_password"),
                UserStatus.valueOf(resultSet.getString("status")),
                resultSet.getInt("failed_login_count"),
                lockedUntil == null ? null : lockedUntil.toInstant()
        );
    }
}
