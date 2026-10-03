package vn.codegym.salesinventory.service;
import java.sql.SQLException;
import java.io.IOException;
import java.util.Map;
import javax.sql.DataSource;
import vn.codegym.salesinventory.dao.Sql;
import vn.codegym.salesinventory.validation.PhoneNumber;
import vn.codegym.salesinventory.validation.ProfileValidationException;
import vn.codegym.salesinventory.validation.ProfileValidator;
public final class ProfileService {
    private final DataSource source;
    private final ImageStorage images;
    public ProfileService(DataSource source) { this(source,ImageStorage.configured()); }
    public ProfileService(DataSource source,ImageStorage images) { this.source=source; this.images=images; }
    public Map<String,Object> find(long userId) {
        new AccessService(source).load(userId).require("PROFILE");
        return Sql.transaction(source,c -> Sql.one(c,"SELECT username,email,full_name,phone,avatar_key FROM users WHERE id=?",userId));
    }
    public void update(long userId,String name,String phone) {
        saveContact(userId,name,phone,null);
    }
    public void updateWithAvatar(long userId,String name,String phone,byte[] bytes) throws IOException {
        new AccessService(source).load(userId).require("PROFILE");
        var errors=ProfileValidator.validate(name,phone);
        if(!errors.isEmpty()) throw new ProfileValidationException(errors);
        String key=images.save(bytes);
        String old;
        try { old=saveContact(userId,name,phone,key); }
        catch(RuntimeException failure) {
            try { images.remove(key); } catch(IOException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
        try { images.remove(old); }
        catch(IOException cleanup) {
            org.slf4j.LoggerFactory.getLogger(ProfileService.class).warn("Không thể dọn ảnh đại diện cũ sau commit",cleanup);
        }
    }
    private String saveContact(long userId,String name,String phone,String key) {
        new AccessService(source).load(userId).require("PROFILE");
        var errors=ProfileValidator.validate(name,phone);
        if(!errors.isEmpty()) throw new ProfileValidationException(errors);
        String normalized=PhoneNumber.normalize(phone);
        return Sql.transaction(source,c -> {
            var before=Sql.one(c,"SELECT full_name,phone,avatar_key FROM users WHERE id=? FOR UPDATE",userId);
            if(!Sql.query(c,"SELECT id FROM users WHERE phone_normalized=? AND id<>?",normalized,userId).isEmpty()) throw duplicatePhone();
            try {
                Sql.update(c,"UPDATE users SET full_name=?,phone=?,phone_normalized=?,version=version+1 WHERE id=?",name.trim(),normalized,normalized,userId);
            } catch(SQLException exception) {
                // The unique index is authoritative when concurrent requests pass the pre-check.
                if(exception.getErrorCode()==1062 && "23000".equals(exception.getSQLState())
                        && exception.getMessage().contains("uk_users_phone_normalized")) throw duplicatePhone();
                throw exception;
            }
            AuditService.record(c,userId,"PROFILE_UPDATED","USER",userId,before,Map.of("full_name",name.trim(),"phone",normalized));
            if(key!=null) {
                Sql.update(c,"UPDATE users SET avatar_key=? WHERE id=?",key,userId);
                AuditService.record(c,userId,"AVATAR_UPDATED","USER",userId,java.util.Collections.singletonMap("avatar_key",before.get("avatar_key")),Map.of("avatar_key",key));
            }
            return Sql.text(before.get("avatar_key"));
        });
    }
    private static ProfileValidationException duplicatePhone() {
        return new ProfileValidationException(Map.of("phone","Số điện thoại đã được sử dụng."));
    }
}
