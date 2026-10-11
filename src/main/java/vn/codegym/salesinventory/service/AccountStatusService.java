package vn.codegym.salesinventory.service;
import javax.sql.DataSource;
import java.time.Instant;
import vn.codegym.salesinventory.dao.*;
public final class AccountStatusService {
    private final DataSource source;
    public AccountStatusService(DataSource source) { this.source=source; }
    public void change(long actor,long target,boolean lock,String reason) {
        new AccessService(source).load(actor).require("USER_MANAGE");
        if(actor==target) throw new IllegalArgumentException("Không thể tự khóa hoặc thay đổi trạng thái quản trị của chính mình.");
        if(lock && (reason==null || reason.isBlank() || reason.length()>1000)) throw new IllegalArgumentException("Phải ghi lý do khóa, tối đa 1.000 ký tự.");
        Sql.transaction(source,c -> {
            var old=Sql.one(c,"SELECT status,status_before_lock,lock_reason,account_kind FROM users WHERE id=? FOR UPDATE",target);if("DEALER".equals(old.get("account_kind")))throw new SecurityException("Quản lý trạng thái tài khoản đại lý qua cổng.");String status=Sql.text(old.get("status"));Instant now=Instant.now();
            if(lock) {
                Sql.update(c,"UPDATE users SET status_before_lock=CASE WHEN status='ADMIN_LOCKED' THEN status_before_lock ELSE status END,status='ADMIN_LOCKED',lock_reason=?,locked_by=?,admin_locked_at=?,version=version+1 WHERE id=?",reason.trim(),actor,java.sql.Timestamp.from(now),target);
                new JdbcSessionRepository().revokeAllForUser(c,target,now,"ADMIN_LOCKED");
                Sql.update(c,"INSERT INTO handover_warnings(user_id,dealer_reference,reason,created_at) SELECT user_id,dealer_reference,?,? FROM dealer_staff_references WHERE user_id=? AND NOT EXISTS(SELECT 1 FROM handover_warnings h WHERE h.user_id=dealer_staff_references.user_id AND h.dealer_reference=dealer_staff_references.dealer_reference AND h.resolved_at IS NULL)",reason.trim(),java.sql.Timestamp.from(now),target);
            } else {
                if(!status.equals("ADMIN_LOCKED")) throw new IllegalArgumentException("Tài khoản hiện không bị quản trị viên khóa.");
                String restored=Sql.text(old.get("status_before_lock"));if(!java.util.Set.of("ACTIVE","DISABLED","PENDING_ACTIVATION").contains(restored)) restored="DISABLED";
                Sql.update(c,"UPDATE users SET status=?,lock_reason=NULL,locked_by=NULL,admin_locked_at=NULL,status_before_lock=NULL,failed_login_count=0,locked_until=NULL,version=version+1 WHERE id=?",restored,target);
            }
            AuditService.record(c,actor,lock?"ACCOUNT_LOCKED":"ACCOUNT_UNLOCKED","USER",target,old,Sql.one(c,"SELECT status,lock_reason FROM users WHERE id=?",target));return null;
        });
    }
}
