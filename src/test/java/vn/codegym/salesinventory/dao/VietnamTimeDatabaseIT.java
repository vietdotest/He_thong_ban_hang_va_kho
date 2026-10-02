package vn.codegym.salesinventory.dao;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.zaxxer.hikari.HikariDataSource;
import db.migration.V016__Vietnam_datetime;
import java.sql.Timestamp;
import java.time.*;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.migration.Context;
import org.junit.jupiter.api.*;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.mysql.MySQLContainer;
import vn.codegym.salesinventory.config.*;
import vn.codegym.salesinventory.service.AuditService;

@Testcontainers
class VietnamTimeDatabaseIT {
    @Container static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4.11")
            .withDatabaseName("timezone_acceptance").withUsername("test").withPassword("test")
            .withCommand("--log-bin-trust-function-creators=1");
    private HikariDataSource migration;
    private HikariDataSource runtime;
    private final Instant now = Instant.parse("2026-10-02T18:28:46.123456Z");

    @BeforeEach void prepare() {
        var settings = new AppConfig.DatabaseSettings(MYSQL.getJdbcUrl()+"?serverTimezone=UTC", MYSQL.getUsername(), MYSQL.getPassword(), 3, 1, 10000);
        migration = DatabaseFactory.createMigrationSource(settings);
        Flyway.configure().dataSource(migration).cleanDisabled(false).load().clean();
        Flyway.configure().dataSource(migration).target("15").load().migrate();
        Sql.transaction(migration,c->{Sql.insert(c,"INSERT INTO audit_logs(actor_user_id,event_type) VALUES(1,'LEGACY_TIME')");return null;});
        runtime = DatabaseFactory.create(settings);
    }

    @AfterEach void close() { if(runtime!=null)runtime.close(); if(migration!=null)migration.close(); }
    private void upgrade() { Flyway.configure().dataSource(migration).load().migrate(); }

    @Test void oldDatetimeMovesOnceAndPreservesNullsAndHistoricalUpdatedAt() {
        Sql.transaction(migration,c->{Sql.update(c,"UPDATE users SET created_at=?,updated_at=?,last_login_at=?,locked_until=NULL WHERE id=1",Timestamp.from(now),Timestamp.from(now.minusSeconds(100)),Timestamp.from(now));return null;});
        upgrade();
        var row=Sql.transaction(runtime,c->Sql.one(c,"SELECT created_at,updated_at,last_login_at,locked_until FROM users WHERE id=1"));
        assertThat(Sql.instant(row.get("created_at"))).isEqualTo(now);
        assertThat(Sql.instant(row.get("updated_at"))).isEqualTo(now.minusSeconds(100));
        assertThat(Sql.instant(row.get("last_login_at"))).isEqualTo(now);
        assertThat(row.get("locked_until")).isNull();
        assertThat(row.get("created_at").toString()).isEqualTo("2026-10-03T01:28:46.123456");
        upgrade();
        assertThat(Sql.transaction(runtime,c->Sql.one(c,"SELECT created_at FROM users WHERE id=1")).get("created_at")).isEqualTo(row.get("created_at"));
    }

    @Test void jdbcReadWriteAndSessionClockAgreeEvenWhenJvmUsesUtc() {
        upgrade();
        Sql.transaction(runtime,c->{Sql.update(c,"UPDATE users SET locked_until=? WHERE id=1",Timestamp.from(now));return null;});
        var row=Sql.transaction(runtime,c->Sql.one(c,"SELECT locked_until,@@session.time_zone AS zone FROM users WHERE id=1"));
        assertThat(row.get("zone")).isEqualTo("+07:00");
        assertThat(Sql.instant(row.get("locked_until"))).isEqualTo(now);
        try(var c=runtime.getConnection();var s=c.prepareStatement("SELECT locked_until FROM users WHERE id=1");var r=s.executeQuery()) {
            r.next();assertThat(r.getTimestamp(1).toInstant()).isEqualTo(now);
        } catch(Exception e) {throw new AssertionError(e);}
    }

    @Test void activationAndSessionsKeepExactlyTheirOriginalExpiryInstants() {
        Sql.transaction(migration,c->{
            Sql.insert(c,"INSERT INTO activation_tokens(user_id,token_hash,expires_at) VALUES(1,?,?)","a".repeat(64),Timestamp.from(now.plusSeconds(86400)));
            Sql.update(c,"INSERT INTO user_sessions(id,user_id,token_hash,created_at,last_activity_at,expires_at) VALUES(?,1,?,?,?,?)","timezone-session","b".repeat(64),Timestamp.from(now),Timestamp.from(now),Timestamp.from(now.plusSeconds(28800)));
            return null;
        });
        upgrade();
        var session=Sql.transaction(runtime,c->Sql.one(c,"SELECT created_at,expires_at FROM user_sessions WHERE id='timezone-session'"));
        assertThat(Sql.instant(session.get("created_at"))).isEqualTo(now);
        assertThat(Sql.instant(session.get("expires_at"))).isEqualTo(now.plusSeconds(28800));
        var activation=Sql.transaction(runtime,c->Sql.one(c,"SELECT expires_at FROM activation_tokens WHERE token_hash=?","a".repeat(64)));
        assertThat(Sql.instant(activation.get("expires_at"))).isEqualTo(now.plusSeconds(86400));
    }

    @Test void nativeTimestampAndBusinessDateAreNotShifted() {
        Sql.transaction(migration,c->{Sql.update(c,"ALTER TABLE users ADD COLUMN test_native_time TIMESTAMP(6) NULL,ADD COLUMN test_business_date DATE NULL");Sql.update(c,"UPDATE users SET test_native_time=?,test_business_date='2026-10-02' WHERE id=1",Timestamp.from(now));return null;});
        upgrade();
        var row=Sql.transaction(runtime,c->Sql.one(c,"SELECT test_native_time,test_business_date FROM users WHERE id=1"));
        assertThat(Sql.instant(row.get("test_native_time"))).isEqualTo(now);
        assertThat(row.get("test_business_date").toString()).isEqualTo("2026-10-02");
    }

    @Test void partialMigrationFailureRollsBackAllDatetimeTables() throws Exception {
        var before=Sql.transaction(migration,c->Sql.one(c,"SELECT occurred_at FROM audit_logs ORDER BY id LIMIT 1"));
        Sql.transaction(migration,c->{Sql.update(c,"CREATE TRIGGER timezone_reject BEFORE UPDATE ON users FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='timezone failure'");return null;});
        try(var c=migration.getConnection()) {
            Context context=mock(Context.class);when(context.getConnection()).thenReturn(c);
            assertThatThrownBy(()->new V016__Vietnam_datetime().migrate(context)).hasMessageContaining("timezone failure");
        }
        var afterFailure=Sql.transaction(migration,c->Sql.one(c,"SELECT occurred_at FROM audit_logs ORDER BY id LIMIT 1"));
        var marker=Sql.transaction(migration,c->Sql.query(c,"SELECT * FROM application_time_zone"));
        assertThat(afterFailure).isEqualTo(before);
        assertThat(marker).isEmpty();
        Sql.transaction(migration,c->{Sql.update(c,"DROP TRIGGER timezone_reject");return null;});
        upgrade();
    }

    @Test void committedMarkerPreventsDoubleShiftIfFlywayHistoryMustBeRetried() throws Exception {
        try(var c=migration.getConnection()) {
            Context context=mock(Context.class);when(context.getConnection()).thenReturn(c);
            new V016__Vietnam_datetime().migrate(context);
        }
        var before=Sql.transaction(runtime,c->Sql.one(c,"SELECT created_at FROM users WHERE id=1"));
        upgrade();
        var after=Sql.transaction(runtime,c->Sql.one(c,"SELECT created_at FROM users WHERE id=1"));
        assertThat(after).isEqualTo(before);
    }

    @Test void newDefaultsAndAuditUseVietnamClock() {
        upgrade();
        Sql.transaction(runtime,c->{AuditService.record(c,1,"TIMEZONE_TEST","USER",1,null,null);return null;});
        var row=Sql.transaction(runtime,c->Sql.one(c,"SELECT occurred_at,NOW(6) AS current_time_vn FROM audit_logs WHERE event_type='TIMEZONE_TEST'"));
        assertThat(Duration.between(Sql.instant(row.get("occurred_at")),Instant.now()).abs()).isLessThan(Duration.ofSeconds(5));
        assertThat(Duration.between(Sql.instant(row.get("occurred_at")),Sql.instant(row.get("current_time_vn"))).abs()).isLessThan(Duration.ofSeconds(5));
    }
}
