package vn.codegym.salesinventory.service;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import javax.sql.DataSource;
import vn.codegym.salesinventory.dao.AuditLogRepository;
import vn.codegym.salesinventory.dao.SessionRepository;
import vn.codegym.salesinventory.dao.UserManagementRepository;
import vn.codegym.salesinventory.dto.AuthenticationContext;
import vn.codegym.salesinventory.dto.UserAccountCommand;
import vn.codegym.salesinventory.dto.UserManagementResult;
import vn.codegym.salesinventory.dto.UserPage;
import vn.codegym.salesinventory.dto.UserSearchCriteria;
import vn.codegym.salesinventory.exception.AuthenticationException;
import vn.codegym.salesinventory.model.ManagedUser;
import vn.codegym.salesinventory.model.RoleOption;
import vn.codegym.salesinventory.model.UserStatus;
import vn.codegym.salesinventory.security.PasswordHasher;
import vn.codegym.salesinventory.security.TemporaryPasswordGenerator;

public final class UserManagementService {
    private final DataSource dataSource;
    private final UserManagementRepository users;
    private final SessionRepository sessions;
    private final AuditLogRepository audits;
    private final PasswordHasher passwordHasher;
    private final MailService mailService;
    private final TemporaryPasswordGenerator passwordGenerator;
    private final Clock clock;
    private ActivationService activationService;

    public UserManagementService(
            DataSource dataSource,
            UserManagementRepository users,
            SessionRepository sessions,
            AuditLogRepository audits,
            PasswordHasher passwordHasher,
            MailService mailService,
            TemporaryPasswordGenerator passwordGenerator,
            Clock clock
    ) {
        this.dataSource = dataSource;
        this.users = users;
        this.sessions = sessions;
        this.audits = audits;
        this.passwordHasher = passwordHasher;
        this.mailService = mailService;
        this.passwordGenerator = passwordGenerator;
        this.clock = clock;
    }

    public UserManagementService(DataSource source,UserManagementRepository users,SessionRepository sessions,AuditLogRepository audits,PasswordHasher hasher,MailService mail,TemporaryPasswordGenerator generator,Clock clock,ActivationService activation) {
        this(source,users,sessions,audits,hasher,mail,generator,clock); this.activationService=activation;
    }

    public UserPage search(UserSearchCriteria requested) {
        try (Connection connection = dataSource.getConnection()) {
            long total = users.count(connection, requested);
            int totalPages = (int)Math.min(Integer.MAX_VALUE, Math.max(1, total / requested.pageSize() + (total % requested.pageSize() == 0 ? 0 : 1)));
            UserSearchCriteria effective = requested.page() > totalPages
                    ? new UserSearchCriteria(requested.keyword(), requested.roleCode(), requested.status(), totalPages, requested.pageSize())
                    : requested;
            return new UserPage(users.search(connection, effective), total, effective.page(),
                    effective.pageSize());
        } catch (SQLException exception) {
            throw new AuthenticationException("User search failed", exception);
        }
    }

    public List<RoleOption> roles() {
        try (Connection connection = dataSource.getConnection()) {
            return users.findRoles(connection);
        } catch (SQLException exception) {
            throw new AuthenticationException("Role lookup failed", exception);
        }
    }

    public Optional<ManagedUser> find(long userId) {
        try (Connection connection = dataSource.getConnection()) {
            return users.findByIdForUpdate(connection, userId);
        } catch (SQLException exception) {
            throw new AuthenticationException("User lookup failed", exception);
        }
    }

    public UserManagementResult create(
            UserAccountCommand command,
            long actorUserId,
            AuthenticationContext context
    ) {
        return createAssigned(command,actorUserId,context,null,null,null);
    }
    public UserManagementResult createAssigned(UserAccountCommand command,long actorUserId,AuthenticationContext context,java.util.Set<String> roles,java.util.Set<Long> warehouses,java.util.Set<Long> territories) {
        if("DEALER".equals(command.roleCode()))return UserManagementResult.failure(UserManagementResult.Status.INVALID_ROLE);
        if(roles!=null) AssignmentService.validate(actorUserId,-1,roles,warehouses);
        Instant now = clock.instant();
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                if (!users.userHasRole(connection, actorUserId, "ADMIN") && !users.userHasPermission(connection, actorUserId, "USER_MANAGE")) {
                    connection.rollback();
                    return UserManagementResult.failure(UserManagementResult.Status.FORBIDDEN);
                }
                if (!users.roleExists(connection, command.roleCode())) {
                    connection.rollback();
                    return UserManagementResult.failure(UserManagementResult.Status.INVALID_ROLE);
                }
                String temporaryPassword = passwordGenerator.generate();
                UserAccountCommand effective = activationService == null ? command : new UserAccountCommand(command.username(),command.email(),command.fullName(),command.phone(),command.roleCode(),UserStatus.PENDING_ACTIVATION,command.version());
                long userId = users.create(connection, effective, passwordHasher.hash(temporaryPassword), now);
                users.replaceRole(connection, userId, command.roleCode());
                if(roles!=null) AssignmentService.replace(connection,userId,roles,warehouses,territories);
                if(activationService!=null) AuditService.record(connection,actorUserId,"USER_CREATED","USER",userId,null,java.util.Map.of("username",command.username(),"email",command.email(),"full_name",command.fullName(),"phone",command.phone(),"status",effective.status().name()));
                else audits.record(connection, actorUserId, "USER_CREATED",
                        "targetUserId=" + userId + ";role=" + command.roleCode() + ";status=" + command.status(),
                        context.ipAddress(), context.userAgent(), now);
                try {
                    if(activationService == null) mailService.sendTemporaryPassword(command.email(),command.fullName(),command.username(),temporaryPassword);
                    else activationService.send(command.email(),command.fullName(),command.username(),temporaryPassword,activationService.issue(connection,userId));
                } catch (RuntimeException deliveryFailure) {
                    connection.rollback();
                    return UserManagementResult.failure(UserManagementResult.Status.EMAIL_DELIVERY_FAILED);
                }
                connection.commit();
                return UserManagementResult.success(userId);
            } catch (SQLException exception) {
                rollbackQuietly(connection);
                UserManagementResult duplicate = duplicateResult(exception);
                if (duplicate != null) {
                    return duplicate;
                }
                throw exception;
            } catch (RuntimeException exception) {
                rollbackQuietly(connection);
                throw exception;
            }
        } catch (SQLException exception) {
            throw new AuthenticationException("Creating a user failed", exception);
        }
    }

    /** Worker owns the row transaction and its durable pre-SMTP attempt marker. */
    long createImport(Connection connection,UserAccountCommand command,long actor,java.util.Set<String> roles,
                      java.util.Set<Long> warehouses,java.util.Set<Long> territories,Runnable beforeSend)throws SQLException {
        DealerService.access(connection,actor,"USER_MANAGE");
        AssignmentService.validate(actor,-1,roles,warehouses);
        if(activationService==null)throw new IllegalStateException("Nhập tài khoản cần cấu hình kích hoạt email.");
        if("DEALER".equals(command.roleCode()))throw new SecurityException("Không nhập tài khoản cổng qua chức năng nội bộ.");
        var effective=new UserAccountCommand(command.username(),command.email(),command.fullName(),command.phone(),command.roleCode(),UserStatus.PENDING_ACTIVATION,0);
        String password=passwordGenerator.generate();long user=users.create(connection,effective,passwordHasher.hash(password),clock.instant());
        AssignmentService.replace(connection,user,roles,warehouses,territories);
        AuditService.record(connection,actor,"USER_CREATED","USER",user,null,java.util.Map.of("username",effective.username(),"email",effective.email(),"full_name",effective.fullName(),"phone",effective.phone(),"status","PENDING_ACTIVATION"));
        String token=activationService.issue(connection,user);
        beforeSend.run();activationService.send(effective.email(),effective.fullName(),effective.username(),password,token);
        return user;
    }

    public UserManagementResult update(
            long userId,
            UserAccountCommand command,
            long actorUserId,
            AuthenticationContext context
    ) {
        Instant now = clock.instant();
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                if (!users.userHasRole(connection, actorUserId, "ADMIN") && !users.userHasPermission(connection, actorUserId, "USER_MANAGE")) {
                    connection.rollback();
                    return UserManagementResult.failure(UserManagementResult.Status.FORBIDDEN);
                }
                Optional<ManagedUser> found = users.findByIdForUpdate(connection, userId);
                if (found.isEmpty()) {
                    connection.rollback();
                    return UserManagementResult.failure(UserManagementResult.Status.NOT_FOUND);
                }
                if(users.isPortalAccount(connection,userId)||"DEALER".equals(command.roleCode())){connection.rollback();return UserManagementResult.failure(UserManagementResult.Status.FORBIDDEN);}
                if (!users.roleExists(connection, command.roleCode())) {
                    connection.rollback();
                    return UserManagementResult.failure(UserManagementResult.Status.INVALID_ROLE);
                }
                if (userId == actorUserId
                        && (command.status() != UserStatus.ACTIVE || !"ADMIN".equals(command.roleCode()))) {
                    connection.rollback();
                    return UserManagementResult.failure(UserManagementResult.Status.SELF_PROTECTION);
                }
                if(command.status()!=found.get().status()) {
                    connection.rollback(); return UserManagementResult.failure(UserManagementResult.Status.FORBIDDEN);
                }
                if (users.update(connection, userId, command) != 1) {
                    connection.rollback();
                    return UserManagementResult.failure(UserManagementResult.Status.STALE_UPDATE);
                }
                /* Vai trò được cập nhật riêng qua AssignmentService. */
                if (command.status() != UserStatus.ACTIVE) {
                    sessions.revokeAllForUser(connection, userId, now, "ACCOUNT_STATUS_CHANGED");
                }
                if(activationService!=null) AuditService.record(connection,actorUserId,"USER_UPDATED","USER",userId,java.util.Map.of("email",found.get().email(),"full_name",found.get().fullName(),"phone",java.util.Objects.toString(found.get().phone(),"")),java.util.Map.of("email",command.email(),"full_name",command.fullName(),"phone",command.phone()));
                else audits.record(connection, actorUserId, "USER_UPDATED",
                        "targetUserId=" + userId + ";role=" + command.roleCode() + ";status=" + command.status(),
                        context.ipAddress(), context.userAgent(), now);
                connection.commit();
                return UserManagementResult.success(userId);
            } catch (SQLException exception) {
                rollbackQuietly(connection);
                UserManagementResult duplicate = duplicateResult(exception);
                if (duplicate != null) {
                    return duplicate;
                }
                throw exception;
            } catch (RuntimeException exception) {
                rollbackQuietly(connection);
                throw exception;
            }
        } catch (SQLException exception) {
            throw new AuthenticationException("Updating a user failed", exception);
        }
    }

    private static UserManagementResult duplicateResult(SQLException exception) {
        String message = exception.getMessage() == null ? "" : exception.getMessage().toLowerCase(Locale.ROOT);
        if (!"23000".equals(exception.getSQLState()) && !message.contains("duplicate")) {
            return null;
        }
        if (message.contains("username")) {
            return UserManagementResult.failure(UserManagementResult.Status.DUPLICATE_USERNAME);
        }
        if (message.contains("email")) {
            return UserManagementResult.failure(UserManagementResult.Status.DUPLICATE_EMAIL);
        }
        if (message.contains("phone")) {
            return UserManagementResult.failure(UserManagementResult.Status.DUPLICATE_PHONE);
        }
        return null;
    }

    private static void rollbackQuietly(Connection connection) {
        try {
            connection.rollback();
        } catch (SQLException ignored) {
            // Preserve the original exception.
        }
    }
}
