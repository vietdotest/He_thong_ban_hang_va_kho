package vn.codegym.salesinventory.controller;
import jakarta.servlet.http.*;
import java.nio.file.*;
import vn.codegym.salesinventory.dao.Sql;
import vn.codegym.salesinventory.service.ImageStorage;
import vn.codegym.salesinventory.service.AvatarService;
import vn.codegym.salesinventory.service.ProfileService;
import java.util.Map;
public final class AvatarServlet extends PortalServlet {
    private final AvatarService configured;
    private final ProfileService profile;
    public AvatarServlet() { configured=null; profile=null; }
    AvatarServlet(AvatarService configured,ProfileService profile) { this.configured=configured; this.profile=profile; }
    protected void get(HttpServletRequest r,HttpServletResponse s) throws Exception {
        long id=value(r,"id").isBlank()?actor(r).id():number(r,"id");
        String key=Sql.transaction(source(),c -> Sql.text(Sql.one(c,"SELECT avatar_key FROM users WHERE id=?",id).get("avatar_key")));
        if(key.isBlank()) {s.sendError(404);return;}Path path=ImageStorage.configured().path(key,!value(r,"size").equals("full"));
        if(!Files.exists(path)) {s.sendError(404);return;}s.setContentType("image/png");s.setHeader("X-Content-Type-Options","nosniff");Files.copy(path,s.getOutputStream());
    }
    protected void post(HttpServletRequest r,HttpServletResponse s) throws Exception {
        access(r).require("PROFILE");
        try {
            var part=r.getPart("image");
            if(part==null || part.getSize()==0 || part.getSize()>ImageStorage.MAX_BYTES)
                throw new IllegalArgumentException("Chọn ảnh JPG/PNG tối đa 2MB.");
            byte[] bytes;try(var input=part.getInputStream()) {bytes=input.readNBytes(ImageStorage.MAX_BYTES+1);}
            (configured==null ? new AvatarService(source()) : configured).replace(actor(r).id(),bytes);
            redirect(r,s,"/account/profile?notice=saved");
        } catch(IllegalArgumentException invalid) {
            var value=(profile==null ? new ProfileService(source()) : profile).find(actor(r).id());
            r.setAttribute("profile",value);
            r.setAttribute("form",Map.of("fullName",Sql.text(value.get("full_name")),"phone",Sql.text(value.get("phone"))));
            r.setAttribute("errors",Map.of("image",invalid.getMessage()));
            s.setStatus(400);view(r,s,"account/profile");
        }
    }
}
