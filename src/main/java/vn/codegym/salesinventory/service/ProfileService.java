package vn.codegym.salesinventory.service;
import java.sql.SQLException;
import java.util.Map;
import javax.sql.DataSource;
import vn.codegym.salesinventory.dao.Sql;
import vn.codegym.salesinventory.validation.PhoneNumber;
import vn.codegym.salesinventory.validation.ProfileValidationException;
import vn.codegym.salesinventory.validation.ProfileValidator;
public final class ProfileService {
    private final DataSource source;
    public ProfileService(DataSource source) { this.source=source; }
    public Map<String,Object> find(long userId) {
        new AccessService(source).load(userId).require("PROFILE");
        return Sql.transaction(source,c -> Sql.one(c,"SELECT username,email,full_name,phone,avatar_key FROM users WHERE id=?",userId));
    }
    public void update(long userId,String name,String phone) {
        new AccessService(source).load(userId).require("PROFILE");
        var errors=ProfileValidator.validate(name,phone);
        if(!errors.isEmpty()) throw new ProfileValidationException(errors);
        String normalized=PhoneNumber.normalize(phone);
        Sql.transaction(source,c -> {
            var before=Sql.one(c,"SELECT full_name,phone FROM users WHERE id=? FOR UPDATE",userId);
            if(!Sql.query(c,"SELECT id FROM users WHERE phone_normalized=? AND id<>?",normalized,userId).isEmpty()) throw duplicatePhone();
            try {
                Sql.update(c,"UPDATE users SET full_name=?,phone=?,phone_normalized=?,version=version+1 WHERE id=?",name.trim(),normalized,normalized,userId);
            } catch(SQLException exception) {
                // The unique index is authoritative when concurrent requests pass the pre-check.
                if(exception.getErrorCode()==1062 && "23000".equals(exception.getSQLState())
                        && exception.getMessage().contains("uk_users_phone_normalized")) throw duplicatePhone();
                throw exception;
            }
            AuditService.record(c,userId,"PROFILE_UPDATED","USER",userId,before,java.util.Map.of("full_name",name.trim(),"phone",normalized));return null;
        });
    }
    private static ProfileValidationException duplicatePhone() {
        return new ProfileValidationException(Map.of("phone","Số điện thoại đã được sử dụng."));
    }
}
