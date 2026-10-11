package vn.codegym.salesinventory.service;

import java.sql.*;
import java.util.*;
import javax.sql.DataSource;
import vn.codegym.salesinventory.dao.Sql;
import vn.codegym.salesinventory.dto.*;
import vn.codegym.salesinventory.security.Access;

/** Read permission is not permission to disclose every business object's history. */
public final class AuditReadService {
    private final DataSource source;
    public AuditReadService(DataSource source){this.source=source;}
    record Scope(Access access,String sql,List<Object> args) { }
    static final String LEGACY="(a.object_type IS NULL OR a.object_type NOT IN ('DEALER','DEALER_ADDRESS','ORDER','DISCOUNT_POLICY','PORTAL_ACCOUNT','IMPORT_JOB'))";
    static Scope scope(Connection c,long actor)throws SQLException{
        Access a=DealerService.access(c,actor,"AUDIT_READ");
        if(PortalAccountService.portal(c,actor))throw new SecurityException();
        boolean dealers=a.allows("DEALER_READ"),all=DealerService.all(a),orders=a.allows("ORDER_READ"),portal=a.roles().contains("SALES_MANAGER")&&a.allows("PORTAL_ACCOUNT_MANAGE");
        String sql="(("+LEGACY+")"
            +" OR (a.object_type='DEALER' AND ? AND (? OR EXISTS(SELECT 1 FROM dealers d WHERE d.id=a.object_id AND d.primary_staff_id=?)))"
            +" OR (a.object_type='DEALER_ADDRESS' AND ? AND (? OR EXISTS(SELECT 1 FROM dealer_addresses da JOIN dealers d ON d.id=da.dealer_id WHERE da.id=a.object_id AND d.primary_staff_id=?)))"
            +" OR (a.object_type='ORDER' AND ? AND EXISTS(SELECT 1 FROM orders o JOIN dealers d ON d.id=o.dealer_id WHERE o.id=a.object_id AND (? OR d.primary_staff_id=?)))"
            +" OR (a.object_type='DISCOUNT_POLICY' AND ?)"
            +" OR (a.object_type='PORTAL_ACCOUNT' AND ?)"
            +" OR (a.object_type='IMPORT_JOB' AND a.actor_user_id=? AND ((JSON_UNQUOTE(JSON_EXTRACT(IF(JSON_VALID(a.after_values),a.after_values,'{}'),'$.kind'))='USER' AND ?) OR (JSON_UNQUOTE(JSON_EXTRACT(IF(JSON_VALID(a.after_values),a.after_values,'{}'),'$.kind'))='PRODUCT' AND ?))))"
            +" AND (? OR (NOT EXISTS(SELECT 1 FROM users au WHERE au.id=a.actor_user_id AND au.account_kind='DEALER') AND (a.object_type IS NULL OR a.object_type<>'USER' OR NOT EXISTS(SELECT 1 FROM users ou WHERE ou.id=a.object_id AND ou.account_kind='DEALER'))))";
        return new Scope(a,sql,List.of(dealers,all,actor,dealers,all,actor,orders&&dealers,all&&a.allows("ORDER_READ_ALL"),actor,a.allows("DISCOUNT_READ"),portal,actor,a.allows("USER_MANAGE"),a.allows("PRODUCT_MANAGE"),portal));
    }
    private static List<Object> arguments(Object[] filterArgs,Scope scope){var args=new ArrayList<>(Arrays.asList(filterArgs));args.addAll(scope.args);return args;}
    private static String where(String filter,String scope){return (filter.isBlank()?" WHERE 1=1 ":filter)+" AND ("+scope+")";}
    public PageResult<Map<String,Object>> search(long actor,String filter,Object[] filterArgs,String query,PageRequest request){
        String q=limited(query),pattern="%"+q+"%";
        return Sql.snapshot(source,c->{var scope=scope(c,actor);String where=where(filter,scope.sql)+" AND (?='' OR u.full_name LIKE ? OR u.username LIKE ? OR a.event_type LIKE ? OR CAST(a.object_id AS CHAR) LIKE ?)";
            var args=arguments(filterArgs,scope);args.addAll(List.of(q,pattern,pattern,pattern,pattern));
            long total=Sql.id(Sql.one(c,"SELECT COUNT(*) total FROM audit_logs a LEFT JOIN users u ON u.id=a.actor_user_id"+where,args.toArray()).get("total"));var page=request.clamp(total);
            args.add(page.pageSize());args.add(page.offset());String cost=scope.access.allows("COST_READ")?",a.before_cost,a.after_cost":"";
            var rows=Sql.query(c,"SELECT a.id,a.event_type,a.object_type,a.object_id,a.before_values,a.after_values,a.occurred_at,u.full_name"+cost+" FROM audit_logs a LEFT JOIN users u ON u.id=a.actor_user_id"+where+" ORDER BY a.occurred_at DESC,a.id DESC LIMIT ? OFFSET ?",args.toArray());decorate(rows,true);
            return new PageResult<>(rows,total,page.page(),page.pageSize());});
    }
    public List<Map<String,Object>> recent(long actor){return Sql.snapshot(source,c->{var scope=scope(c,actor);var rows=Sql.query(c,"SELECT a.event_type,a.object_type,a.object_id,a.occurred_at,u.full_name FROM audit_logs a LEFT JOIN users u ON u.id=a.actor_user_id WHERE "+scope.sql+" ORDER BY a.occurred_at DESC,a.id DESC LIMIT 5",scope.args.toArray());decorate(rows,false);return rows;});}
    public Map<String,List<Map<String,Object>>> options(long actor,String selectedActor){return Sql.snapshot(source,c->{var scope=scope(c,actor);var types=Sql.query(c,"SELECT DISTINCT a.object_type FROM audit_logs a WHERE a.object_type IS NOT NULL AND "+scope.sql+" ORDER BY a.object_type",scope.args.toArray());for(var row:types)row.put("object_label",AuditService.objectLabel(Sql.text(row.get("object_type"))));
        var users=new ArrayList<Map<String,Object>>();if(selectedActor!=null&&selectedActor.matches("[1-9][0-9]{0,17}")){var args=new ArrayList<>(scope.args);args.add(Long.parseLong(selectedActor));users.addAll(Sql.query(c,"SELECT DISTINCT u.id,u.username code,u.full_name FROM audit_logs a JOIN users u ON u.id=a.actor_user_id WHERE "+scope.sql+" AND u.id=? LIMIT 1",args.toArray()));}return Map.of("users",users,"types",types);});}
    public List<Map<String,Object>> suggestActors(long actor,String query){String q=limited(query);return Sql.snapshot(source,c->{var scope=scope(c,actor);if(q.length()<2)return List.of();var args=new ArrayList<>(scope.args);args.addAll(List.of("%"+q+"%","%"+q+"%",q,q+"%"));return Sql.query(c,"SELECT DISTINCT u.id,u.username code,u.full_name name FROM audit_logs a JOIN users u ON u.id=a.actor_user_id WHERE "+scope.sql+" AND (u.username LIKE ? OR u.full_name LIKE ?) ORDER BY (u.username=?) DESC,(u.username LIKE ?) DESC,u.full_name,u.id LIMIT 10",args.toArray());});}
    private static String limited(String q){q=q==null?"":q.trim();return q.length()>150?q.substring(0,150):q;}
    private static void decorate(List<Map<String,Object>> rows,boolean seconds){for(var row:rows){row.put("display_time",Sql.instant(row.get("occurred_at")).atZone(java.time.ZoneId.of("Asia/Ho_Chi_Minh")).format(java.time.format.DateTimeFormatter.ofPattern(seconds?"dd/MM/yyyy HH:mm:ss":"dd/MM/yyyy HH:mm")));row.put("event_label",AuditService.eventLabel(Sql.text(row.get("event_type"))));row.put("object_label",AuditService.objectLabel(Sql.text(row.get("object_type"))));}}
}
