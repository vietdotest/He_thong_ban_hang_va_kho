package vn.codegym.salesinventory.service;
import javax.sql.DataSource;
import vn.codegym.salesinventory.dao.*;
import vn.codegym.salesinventory.validation.PhoneNumber;
public final class ProfileService {
    private final DataSource source;
    public ProfileService(DataSource source) { this.source=source; }
    public void update(long userId,String name,String phone) {
        new AccessService(source).load(userId).require("PROFILE");
        if(name==null || name.trim().length()<2 || name.trim().length()>150) throw new IllegalArgumentException("Họ tên phải có từ 2 đến 150 ký tự.");
        String normalized=PhoneNumber.normalize(phone);
        Sql.transaction(source,c -> {
            var before=Sql.one(c,"SELECT full_name,phone FROM users WHERE id=? FOR UPDATE",userId);
            if(!Sql.query(c,"SELECT id FROM users WHERE phone_normalized=? AND id<>?",normalized,userId).isEmpty()) throw new IllegalArgumentException("Số điện thoại đã được sử dụng.");
            Sql.update(c,"UPDATE users SET full_name=?,phone=?,phone_normalized=?,version=version+1 WHERE id=?",name.trim(),normalized,normalized,userId);
            AuditService.record(c,userId,"PROFILE_UPDATED","USER",userId,before,java.util.Map.of("full_name",name.trim(),"phone",normalized));return null;
        });
    }
}
