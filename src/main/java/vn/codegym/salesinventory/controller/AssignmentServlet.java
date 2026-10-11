package vn.codegym.salesinventory.controller;
import jakarta.servlet.http.*;
import java.util.*;
import vn.codegym.salesinventory.dao.Sql;
import vn.codegym.salesinventory.service.AssignmentService;
public final class AssignmentServlet extends PortalServlet {
    protected void get(HttpServletRequest r,HttpServletResponse s) throws Exception {
        long id=number(r,"id");
        Sql.transaction(source(),c -> {
            r.setAttribute("target",Sql.one(c,"SELECT id,username,full_name FROM users WHERE id=?",id));
            r.setAttribute("roles",Sql.query(c,"SELECT r.*,EXISTS(SELECT 1 FROM user_roles ur WHERE ur.user_id=? AND ur.role_id=r.id) AS selected FROM roles r ORDER BY r.id",id));
            r.setAttribute("warehouses",Sql.query(c,"SELECT w.*,EXISTS(SELECT 1 FROM user_warehouses uw WHERE uw.user_id=? AND uw.warehouse_id=w.id) AS selected FROM warehouses w ORDER BY w.name,w.id",id));
            r.setAttribute("territories",Sql.query(c,"SELECT t.*,EXISTS(SELECT 1 FROM user_territories ut WHERE ut.user_id=? AND ut.territory_id=t.id) AS selected FROM territories t ORDER BY t.name,t.id",id));return null;
        });view(r,s,"admin/assignments");
    }
    protected void post(HttpServletRequest r,HttpServletResponse s) throws Exception {
        new AssignmentService(source()).assign(actor(r).id(),number(r,"id"),new HashSet<>(Arrays.asList(values(r,"role"))),ids(r,"warehouse"),ids(r,"territory"));
        redirect(r,s,"/admin/assignments?id="+number(r,"id")+"&notice=saved");
    }
    protected void badRequest(HttpServletRequest r,HttpServletResponse s,String message) throws jakarta.servlet.ServletException,java.io.IOException {
        r.setAttribute("form",Map.of("roles",List.of(values(r,"role")),"warehouses",List.of(values(r,"warehouse")),"territories",List.of(values(r,"territory"))));
        r.setAttribute("formError",message);s.setStatus(400);
        try { get(r,s); }catch(IllegalArgumentException invalid){super.badRequest(r,s,message);}catch(jakarta.servlet.ServletException|java.io.IOException failure){throw failure;}catch(Exception failure){throw new jakarta.servlet.ServletException(failure);}
    }
    private static String[] values(HttpServletRequest r,String key) { return Optional.ofNullable(r.getParameterValues(key)).orElse(new String[0]); }
    private static Set<Long> ids(HttpServletRequest r,String key) { Set<Long> ids=new HashSet<>(); for(String s:values(r,key)) { try { ids.add(Long.parseLong(s)); } catch(NumberFormatException e) { throw new IllegalArgumentException("Phạm vi không hợp lệ."); } } return ids; }
}
