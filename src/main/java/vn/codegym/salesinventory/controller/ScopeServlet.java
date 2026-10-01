package vn.codegym.salesinventory.controller;
import jakarta.servlet.http.*;
import vn.codegym.salesinventory.dao.Sql;
public final class ScopeServlet extends PortalServlet {
    protected void get(HttpServletRequest r,HttpServletResponse s) throws Exception {
        Sql.transaction(source(),c -> { r.setAttribute("warehouses",Sql.query(c,"SELECT * FROM warehouses ORDER BY name"));r.setAttribute("territories",Sql.query(c,"SELECT * FROM territories ORDER BY name"));return null; }); view(r,s,"admin/scopes");
    }
    protected void post(HttpServletRequest r,HttpServletResponse s) throws Exception {
        access(r).require("USER_MANAGE");String table=switch(value(r,"kind")) { case "warehouse" -> "warehouses";case "territory" -> "territories";default -> throw new IllegalArgumentException("Loại phạm vi không hợp lệ."); };
        String code=value(r,"code"),name=value(r,"name");if(!code.matches("[A-Za-z0-9_-]{1,50}") || name.isBlank() || name.length()>150) throw new IllegalArgumentException("Mã hoặc tên phạm vi không hợp lệ.");
        Sql.transaction(source(),c -> { if(!Sql.query(c,"SELECT id FROM "+table+" WHERE code=?",code).isEmpty()) throw new IllegalArgumentException("Mã phạm vi đã tồn tại.");long id=Sql.insert(c,"INSERT INTO "+table+"(code,name) VALUES(?,?)",code,name);vn.codegym.salesinventory.service.AuditService.record(c,actor(r).id(),"SCOPE_CREATED",table.equals("warehouses")?"WAREHOUSE":"TERRITORY",id,null,java.util.Map.of("code",code,"name",name));return null; });redirect(r,s,"/admin/scopes");
    }
}
