package vn.codegym.salesinventory.service;
import java.util.*;
import javax.sql.DataSource;
import vn.codegym.salesinventory.dao.Sql;
import vn.codegym.salesinventory.dto.*;
import vn.codegym.salesinventory.validation.FieldValidationException;
public final class DealerStatusService {
    private final DataSource source;
    public DealerStatusService(DataSource source){this.source=source;}
    public static boolean locked(Map<String,Object> dealer){return DealerAddressService.isDefault(dealer.get("transaction_locked"));}
    /** Call under the same dealer lock as the INSERT of a NEW order, never for an existing draft. */
    public static void requireNewOrderAllowed(Map<String,Object> dealer){if(locked(dealer))throw FieldValidationException.field("dealer","Đại lý đang bị khóa giao dịch; không thể tạo đơn mới.");if(!"ACTIVE".equals(dealer.get("status")))throw FieldValidationException.field("dealer","Đại lý đã ngừng giao dịch; không thể tạo đơn mới.");}
    public void change(long actor,long dealer,long version,boolean lock,String reason){if(version<1)throw FieldValidationException.field("form","Phiên bản không hợp lệ.");if(reason==null||reason.isBlank()||reason.trim().length()>1000)throw FieldValidationException.field("reason","Khóa hoặc mở giao dịch đều phải ghi lý do, tối đa 1.000 ký tự.");
        Sql.transaction(source,c->{var access=DealerService.access(c,actor,"DEALER_STATUS_MANAGE");if(!access.roles().stream().anyMatch(Set.of("SALES_MANAGER","ACCOUNTANT")::contains))throw new SecurityException("Chỉ Kế toán và Quản lý kinh doanh được khóa/mở đại lý.");var before=DealerService.scoped(c,actor,access,dealer,true);if(Sql.id(before.get("version"))!=version)throw FieldValidationException.field("form","Hồ sơ đã thay đổi. Hãy tải lại.");if(locked(before)==lock)throw FieldValidationException.field("form",lock?"Đại lý đã bị khóa.":"Đại lý không bị khóa giao dịch.");
            Sql.update(c,"UPDATE dealers SET transaction_locked=?,transaction_lock_reason=?,transaction_locked_by=?,transaction_locked_at=CASE WHEN ? THEN CURRENT_TIMESTAMP(6) ELSE NULL END,version=version+1 WHERE id=?",lock,lock?reason.trim():null,lock?actor:null,lock,dealer);
            Sql.insert(c,"INSERT INTO dealer_status_history(dealer_id,actor_id,old_locked,new_locked,reason,dealer_version) VALUES(?,?,?,?,?,?)",dealer,actor,locked(before),lock,reason.trim(),version+1);var after=new LinkedHashMap<>(Sql.one(c,"SELECT id,status,transaction_locked,transaction_lock_reason,transaction_locked_by,transaction_locked_at,version FROM dealers WHERE id=?",dealer));after.put("reason",reason.trim());AuditService.record(c,actor,lock?"DEALER_LOCKED":"DEALER_UNLOCKED","DEALER",dealer,before,after);return null;});
    }
    public PageResult<Map<String,Object>> history(long actor,long dealer,PageRequest request){return Sql.transaction(source,c->{var a=DealerService.access(c,actor,"DEALER_READ");DealerService.scoped(c,actor,a,dealer,false);long total=Sql.id(Sql.one(c,"SELECT COUNT(*) total FROM dealer_status_history WHERE dealer_id=?",dealer).get("total"));var p=request.clamp(total);var rows=Sql.query(c,"SELECT h.old_locked,h.new_locked,h.reason,h.dealer_version,h.changed_at,u.full_name actor_name FROM dealer_status_history h JOIN users u ON u.id=h.actor_id WHERE dealer_id=? ORDER BY h.changed_at DESC,h.id DESC LIMIT ? OFFSET ?",dealer,p.pageSize(),p.offset());return new PageResult<>(rows,total,p.page(),p.pageSize());});}
}
