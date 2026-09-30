package vn.codegym.salesinventory.dao;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import vn.codegym.salesinventory.model.User;

public interface UserRepository {
    Optional<User> findByIdentityForUpdate(Connection connection, String normalizedIdentity) throws SQLException;

    Optional<User> findByIdForUpdate(Connection connection, long userId) throws SQLException;

    Set<String> findRoleCodes(Connection connection, long userId) throws SQLException;

    void updateFailedLogin(Connection connection, long userId, int failureCount, Instant lockedUntil) throws SQLException;

    void recordSuccessfulLogin(Connection connection, long userId, Instant loginTime) throws SQLException;

    void updatePassword(Connection connection, long userId, String passwordHash) throws SQLException;
}
