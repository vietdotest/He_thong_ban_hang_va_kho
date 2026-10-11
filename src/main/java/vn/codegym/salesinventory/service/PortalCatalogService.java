package vn.codegym.salesinventory.service;
import java.sql.*;
import java.time.LocalDate;
import java.util.*;
import javax.sql.DataSource;
import vn.codegym.salesinventory.config.VietnamTime;
import vn.codegym.salesinventory.dao.Sql;
import vn.codegym.salesinventory.dto.*;

/** Dedicated portal projection, never grants general CATALOG_READ or PRICE_READ. */
public final class PortalCatalogService {
    private final DataSource source;public PortalCatalogService(DataSource source){this.source=source;}
    private static final String OFFER=" FROM products p WHERE p.status='ACTIVE' AND EXISTS(SELECT 1 FROM price_items i JOIN price_versions v ON v.id=i.version_id WHERE i.product_id=p.id AND v.group_id=? AND v.valid_from<=? AND v.valid_to>=?)";
    private static long group(Connection c,long actor)throws SQLException{var a=PortalAccountService.access(c,actor,"PORTAL_ORDER_READ");var d=PortalAccountService.scoped(c,actor,a,PortalAccountService.linked(c,actor),false);long group=Sql.id(d.get("group_id"));Sql.one(c,"SELECT id FROM customer_groups WHERE id=? FOR SHARE",group);return group;}
    private static String clean(String query){String q=query==null?"":query.trim();return q.length()>150?q.substring(0,150):q;}
    static void requireOffer(Connection c,long actor,long product)throws SQLException{long group=group(c,actor);LocalDate date=LocalDate.now(VietnamTime.ZONE);if(Sql.query(c,"SELECT p.id"+OFFER+" AND p.id=?",group,date,date,product).isEmpty())throw new SecurityException("Sản phẩm không thuộc danh mục chào bán hiện tại.");}
    public PageResult<Map<String,Object>> search(long actor,String query,PageRequest request){String q=clean(query),term="%"+q+"%";return Sql.transaction(source,c->{long group=group(c,actor);LocalDate date=LocalDate.now(VietnamTime.ZONE);String filter=OFFER+" AND (p.sku LIKE ? OR p.name LIKE ?)";Object[] args={group,date,date,term,term};long total=Sql.id(Sql.one(c,"SELECT COUNT(*) total"+filter,args).get("total"));var p=request.clamp(total);var params=new ArrayList<Object>(Arrays.asList(args));params.add(q);params.add(q+"%");params.add(p.pageSize());params.add(p.offset());return new PageResult<>(Sql.query(c,"SELECT p.id,p.sku,p.name,p.base_unit"+filter+" ORDER BY (p.sku=?) DESC,(p.sku LIKE ?) DESC,p.name,p.id LIMIT ? OFFSET ?",params.toArray()),total,p.page(),p.pageSize());});}
    public List<Map<String,Object>> suggest(long actor,String query){String q=clean(query);if(q.length()<2)return Sql.transaction(source,c->{group(c,actor);return List.of();});return Sql.transaction(source,c->{long group=group(c,actor);LocalDate date=LocalDate.now(VietnamTime.ZONE);return Sql.query(c,"SELECT p.id,p.sku code,p.name"+OFFER+" AND (p.sku LIKE ? OR p.name LIKE ?) ORDER BY (p.sku=?) DESC,(p.sku LIKE ?) DESC,p.name,p.id LIMIT 10",group,date,date,"%"+q+"%","%"+q+"%",q,q+"%");});}
}
