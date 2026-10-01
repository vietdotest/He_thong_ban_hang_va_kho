package vn.codegym.salesinventory.controller;

import jakarta.servlet.http.*;
import java.util.*;
import vn.codegym.salesinventory.dao.Sql;

public final class RoleServlet extends PortalServlet {
    protected void get(HttpServletRequest r,HttpServletResponse s) throws Exception {
        Sql.transaction(source(),c -> {
            r.setAttribute("roles",Sql.query(c,"SELECT * FROM roles ORDER BY id"));
            r.setAttribute("permissions",Sql.query(c,"SELECT * FROM permissions ORDER BY code"));
            r.setAttribute("grants",Sql.query(c,"SELECT role_id,permission_id FROM role_permissions")); return null;
        }); view(r,s,"admin/roles");
    }
    protected void post(HttpServletRequest r,HttpServletResponse s) throws Exception {
        access(r).require("ROLE_MANAGE"); long roleId=number(r,"roleId");
        Set<String> chosen=new HashSet<>(Arrays.asList(Optional.ofNullable(r.getParameterValues("permission")).orElse(new String[0])));
        Sql.transaction(source(),c -> {
            var role=Sql.one(c,"SELECT code FROM roles WHERE id=? FOR UPDATE",roleId);
            if(Sql.text(role.get("code")).equals("ADMIN") && (!chosen.contains("USER_MANAGE") || !chosen.contains("ROLE_MANAGE") || !chosen.contains("PROFILE"))) throw new IllegalArgumentException("Phải giữ quyền quản trị và hồ sơ của vai trò quản trị hệ thống.");
            if(!Sql.text(role.get("code")).equals("SALES_MANAGER") && (chosen.contains("COST_READ") || chosen.contains("COST_WRITE"))) throw new IllegalArgumentException("Giá vốn chỉ dành cho Quản lý kinh doanh.");
            for(String p:chosen) Sql.one(c,"SELECT id FROM permissions WHERE code=?",p);
            var before=Sql.query(c,"SELECT p.code FROM permissions p JOIN role_permissions rp ON rp.permission_id=p.id WHERE rp.role_id=?",roleId);
            Sql.update(c,"DELETE FROM role_permissions WHERE role_id=?",roleId);
            for(String p:chosen) Sql.update(c,"INSERT INTO role_permissions(role_id,permission_id) SELECT ?,id FROM permissions WHERE code=?",roleId,p);
            vn.codegym.salesinventory.service.AuditService.record(c,actor(r).id(),"ROLE_PERMISSIONS_UPDATED","ROLE",roleId,Map.of("permissions",before.toString()),Map.of("permissions",chosen.toString())); return null;
        }); redirect(r,s,"/admin/roles");
    }
}
