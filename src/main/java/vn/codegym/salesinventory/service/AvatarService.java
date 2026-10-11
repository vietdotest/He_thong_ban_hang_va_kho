package vn.codegym.salesinventory.service;

import java.io.IOException;
import java.util.Map;
import javax.sql.DataSource;
import org.slf4j.LoggerFactory;
import vn.codegym.salesinventory.dao.Sql;

public final class AvatarService {
    private final DataSource source;
    private final ImageStorage images;

    public AvatarService(DataSource source) { this(source, ImageStorage.configured()); }
    public AvatarService(DataSource source, ImageStorage images) { this.source = source; this.images = images; }

    public String replace(long actor, byte[] bytes) throws IOException {
        new AccessService(source).load(actor).require("PROFILE");
        String key = images.save(bytes);
        String old;
        try {
            old = Sql.transaction(source, c -> {
                var before = Sql.one(c, "SELECT avatar_key FROM users WHERE id=? FOR UPDATE", actor);
                Sql.update(c, "UPDATE users SET avatar_key=?,version=version+1 WHERE id=?", key, actor);
                AuditService.record(c, actor, "AVATAR_UPDATED", "USER", actor, before, Map.of("avatar_key", key));
                return Sql.text(before.get("avatar_key"));
            });
        } catch (RuntimeException failure) {
            try { images.remove(key); } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
        try { images.remove(old); }
        catch (IOException cleanup) {
            LoggerFactory.getLogger(AvatarService.class).warn("Không thể dọn ảnh đại diện cũ sau commit", cleanup);
        }
        return key;
    }
}
