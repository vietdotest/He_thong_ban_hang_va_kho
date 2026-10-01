package vn.codegym.salesinventory.controller;
import jakarta.servlet.http.*;
import java.nio.file.*;
import vn.codegym.salesinventory.dao.Sql;
import vn.codegym.salesinventory.service.ImageStorage;
public final class AvatarServlet extends PortalServlet {
    protected void get(HttpServletRequest r,HttpServletResponse s) throws Exception {
        long id=value(r,"id").isBlank()?actor(r).id():number(r,"id");
        String key=Sql.transaction(source(),c -> Sql.text(Sql.one(c,"SELECT avatar_key FROM users WHERE id=?",id).get("avatar_key")));
        if(key.isBlank()) {s.sendError(404);return;}Path path=ImageStorage.configured().path(key,!value(r,"size").equals("full"));
        if(!Files.exists(path)) {s.sendError(404);return;}s.setContentType("image/png");s.setHeader("X-Content-Type-Options","nosniff");Files.copy(path,s.getOutputStream());
    }
    protected void post(HttpServletRequest r,HttpServletResponse s) throws Exception {
        var part=r.getPart("image");if(part==null || part.getSize()>ImageStorage.MAX_BYTES)throw new IllegalArgumentException("Chọn ảnh JPG/PNG tối đa 2MB.");
        byte[] bytes;try(var input=part.getInputStream()) {bytes=input.readNBytes(ImageStorage.MAX_BYTES+1);}
        ImageStorage storage=ImageStorage.configured();String key=storage.save(bytes);String old;
        try { old=Sql.transaction(source(),c -> {var before=Sql.one(c,"SELECT avatar_key FROM users WHERE id=? FOR UPDATE",actor(r).id());Sql.update(c,"UPDATE users SET avatar_key=?,version=version+1 WHERE id=?",key,actor(r).id());return Sql.text(before.get("avatar_key"));}); }
        catch(RuntimeException e) {storage.remove(key);throw e;}
        storage.remove(old);redirect(r,s,"/account/profile?notice=saved");
    }
}
