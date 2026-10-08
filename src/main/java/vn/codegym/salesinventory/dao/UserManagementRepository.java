package vn.codegym.salesinventory.dao;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import vn.codegym.salesinventory.dto.UserAccountCommand;
import vn.codegym.salesinventory.dto.UserSearchCriteria;
import vn.codegym.salesinventory.model.ManagedUser;
import vn.codegym.salesinventory.model.RoleOption;

public interface UserManagementRepository {
    boolean isPortalAccount(Connection connection,long userId) throws SQLException;
    default boolean userHasPermission(java.sql.Connection c,long id,String code) throws java.sql.SQLException {
        return !Sql.query(c,"SELECT 1 FROM user_roles ur JOIN role_permissions rp ON rp.role_id=ur.role_id JOIN permissions p ON p.id=rp.permission_id WHERE ur.user_id=? AND p.code=? LIMIT 1",id,code).isEmpty();
    }

    boolean userHasRole(Connection connection, long userId, String roleCode) throws SQLException;

    boolean roleExists(Connection connection, String roleCode) throws SQLException;

    List<RoleOption> findRoles(Connection connection) throws SQLException;

    List<ManagedUser> search(Connection connection, UserSearchCriteria criteria) throws SQLException;

    long count(Connection connection, UserSearchCriteria criteria) throws SQLException;

    Optional<ManagedUser> findByIdForUpdate(Connection connection, long userId) throws SQLException;

    long create(Connection connection, UserAccountCommand command, String passwordHash, Instant createdAt)
            throws SQLException;

    void replaceRole(Connection connection, long userId, String roleCode) throws SQLException;

    int update(Connection connection, long userId, UserAccountCommand command) throws SQLException;
}
