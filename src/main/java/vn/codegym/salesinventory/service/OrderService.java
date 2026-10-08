package vn.codegym.salesinventory.service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.*;
import java.time.*;
import java.util.*;
import javax.sql.DataSource;
import vn.codegym.salesinventory.config.VietnamTime;
import vn.codegym.salesinventory.dao.Sql;
import vn.codegym.salesinventory.dto.*;
import vn.codegym.salesinventory.security.Access;
import vn.codegym.salesinventory.validation.*;

/** Order amounts, scope, prices and snapshots are always established on the server. */
public final class OrderService {
    private final DataSource source;
    private final Clock clock;
    public OrderService(DataSource source){this(source,Clock.systemUTC());}
    public OrderService(DataSource source,Clock clock){this.source=source;this.clock=clock;}
    public record LineInput(long product,long unit,BigDecimal quantity){}
    public record Input(long dealer,long address,LocalDate delivery,long version,String creationKey,List<LineInput> lines){}
    public record LineQuote(int position,long product,String sku,String name,UnitService.Conversion conversion,
                            PricingService.Quote price,DiscountMath.Selection policy,BigDecimal gross,BigDecimal discount,BigDecimal net){}
    public record Quote(LocalDate date,List<LineQuote> lines,BigDecimal gross,BigDecimal discount,BigDecimal net,
                        Map<String,String> errors,String warning,String digest,String token,boolean confirmation){
        public boolean valid(){return errors.isEmpty();}
        /** Explicit projection: no floor prices, internal actor data or costs in the public quote. */
        public Map<String,Object> display(){
            var rows=new ArrayList<Map<String,Object>>();
            for(var l:lines){var row=new LinkedHashMap<String,Object>();row.put("position",l.position);row.put("sku",l.sku);row.put("name",l.name);
                row.put("unit",l.conversion.unitName());row.put("factor",l.conversion.factor().toPlainString());row.put("baseQuantity",l.conversion.baseQuantity().toPlainString());
                row.put("selling",l.price.sellingPrice().toPlainString());row.put("gross",l.gross.toPlainString());row.put("discount",l.discount.toPlainString());row.put("net",l.net.toPlainString());
                row.put("policy",l.policy.applied()?l.policy.tier().name():"");rows.add(row);}
            return Map.of("date",date.toString(),"lines",rows,"gross",gross.toPlainString(),"discount",discount.toPlainString(),"net",net.toPlainString(),"errors",errors,"warning",warning,"token",token,"confirmation",confirmation);
        }
    }
    public record Submission(boolean submitted,long order,Quote quote){}
    private record Context(Access access,Map<String,Object> dealer,Map<String,Object> order){}
    private static boolean all(Access a){return a.allows("ORDER_READ_ALL") && DealerService.all(a);}
    private static Long nullableId(Object v){return v==null?null:Sql.id(v);}
    private static String uuid(String value,String field){try{return UUID.fromString(value).toString();}catch(Exception e){throw FieldValidationException.field(field,"Mã chống lưu/gửi lặp không hợp lệ. Hãy tải lại form.");}}
    private static String digest(Object value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.toString().getBytes(StandardCharsets.UTF_8)));}catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
    public static Map<String,String> errors(Input in){var errors=new LinkedHashMap<String,String>();
        if(in==null)return Map.of("form","Thiếu dữ liệu đơn.");
        if(in.dealer<=0)errors.put("dealer","Chọn đại lý.");if(in.address<=0)errors.put("address","Chọn điểm giao.");
        if(in.delivery==null||in.delivery.getYear()<1000||in.delivery.getYear()>9999)errors.put("delivery","Ngày giao mong muốn không hợp lệ.");
        if(in.version<0)errors.put("form","Phiên bản không hợp lệ.");
        if(in.lines==null||in.lines.isEmpty()||in.lines.size()>100)errors.put("lines","Đơn cần 1–100 dòng hàng.");
        else for(int i=0;i<in.lines.size();i++){var l=in.lines.get(i);if(l==null||l.product<=0||l.unit<=0){errors.put("line"+i,"Chọn sản phẩm và đơn vị.");continue;}
            try{CatalogValidation.decimal(l.quantity,6,true);}catch(IllegalArgumentException e){errors.put("quantity"+i,e.getMessage());}}
        return errors;
    }
    private static void validate(Input in){var errors=errors(in);if(!errors.isEmpty())throw new FieldValidationException(errors);}
    private static Access reader(Connection c,long actor)throws SQLException{if(PortalAccountService.portal(c,actor))return PortalAccountService.access(c,actor,"PORTAL_ORDER_READ");var a=DealerService.access(c,actor,"ORDER_READ");if(a.roles().contains("DEALER"))throw new SecurityException();return a;}
    private static boolean portal(Access a){return a.roles().equals(Set.of("DEALER"));}
    private static void writer(Access a){if(portal(a)){a.require("PORTAL_ORDER_WRITE");return;}a.require("ORDER_WRITE");if(!a.roles().stream().anyMatch(Set.of("SALES_MANAGER","SALES")::contains))throw new SecurityException();}
    private static Map<String,Object> scoped(Connection c,long actor,Access a,long dealer,boolean lock)throws SQLException{return portal(a)?PortalAccountService.scoped(c,actor,a,dealer,lock):DealerService.scoped(c,actor,a,dealer,lock);}
    private static void owner(Map<String,Object> row,long actor){if(Sql.id(row.get("owner_id"))!=actor)throw new SecurityException("Nháp thuộc người khác; Quản lý kinh doanh có thể tiếp quản với lý do.");}
    private static void editable(Map<String,Object> row,long version){if(!"DRAFT".equals(row.get("status")))throw FieldValidationException.field("form","Đơn đã gửi chỉ được xem.");if(Sql.id(row.get("version"))!=version)throw FieldValidationException.field("form","Nháp đã thay đổi. Hãy tải lại trước khi lưu hoặc gửi.");}
    private static Context context(Connection c,long actor,long id,boolean lock)throws SQLException{
        var a=reader(c,actor);var known=Sql.query(c,"SELECT dealer_id FROM orders WHERE id=?",id);if(known.isEmpty())throw new SecurityException("Đơn không thuộc phạm vi được phép.");
        var dealer=scoped(c,actor,a,Sql.id(known.get(0).get("dealer_id")),lock);
        var order=Sql.one(c,"SELECT * FROM orders WHERE id=?"+(lock?" FOR UPDATE":""),id);return new Context(a,dealer,order);
    }
    private static Map<String,Object> address(Connection c,Map<String,Object> dealer,long id)throws SQLException{
        var rows=Sql.query(c,"SELECT * FROM dealer_addresses WHERE id=? AND dealer_id=? FOR SHARE",id,dealer.get("id"));
        if(rows.isEmpty()||!"ACTIVE".equals(rows.get(0).get("status")))throw FieldValidationException.field("address","Điểm giao không thuộc đại lý hoặc đã ngừng sử dụng.");return rows.get(0);
    }
    private static void productLocks(Connection c,List<LineInput> lines)throws SQLException{
        Sql.one(c,"SELECT name FROM catalog_locks WHERE name='CATEGORY_TREE' FOR SHARE");
        for(long product:lines.stream().map(LineInput::product).distinct().sorted().toList())
            if(Sql.query(c,"SELECT id FROM products WHERE id=? FOR SHARE",product).isEmpty())throw FieldValidationException.field("lines","Sản phẩm không còn tồn tại.");
    }
    private static List<LineInput> inputs(Connection c,long order)throws SQLException{
        return Sql.query(c,"SELECT product_id,unit_id,quantity FROM order_lines WHERE order_id=? ORDER BY position",order).stream()
            .map(l->new LineInput(Sql.id(l.get("product_id")),Sql.id(l.get("unit_id")),(BigDecimal)l.get("quantity"))).toList();
    }
    private static String inputHash(Input in){var normalized=new ArrayList<Object>();normalized.add(in.dealer);normalized.add(in.address);normalized.add(in.delivery.toString());
        for(var l:in.lines)normalized.add(List.of(l.product,l.unit,CatalogValidation.decimal(l.quantity,6,true).toPlainString()));return digest(normalized);}
    public long save(long actor,long id,Input in){validate(in);String key=id==0?uuid(in.creationKey,"creationKey"):"",hash=inputHash(in);
        return Sql.transaction(source,c->{var a=reader(c,actor);writer(a);var dealer=scoped(c,actor,a,in.dealer,true);
            if(id==0){var old=Sql.query(c,"SELECT id,creation_hash FROM orders WHERE created_by=? AND creation_key=?",actor,key);
                if(!old.isEmpty()){if(!hash.equals(old.get(0).get("creation_hash")))throw FieldValidationException.field("form","Mã lưu đã dùng cho dữ liệu khác. Hãy mở lại đơn đã lưu.");return Sql.id(old.get(0).get("id"));}
                DealerStatusService.requireNewOrderAllowed(dealer);
            }
            Map<String,Object> before=null;
            if(id!=0){before=Sql.one(c,"SELECT * FROM orders WHERE id=? FOR UPDATE",id);if(Sql.id(before.get("dealer_id"))!=in.dealer)throw new SecurityException();owner(before,actor);editable(before,in.version);}
            address(c,dealer,in.address);productLocks(c,in.lines);var errors=new LinkedHashMap<String,String>();
            for(int i=0;i<in.lines.size();i++){var l=in.lines.get(i);try{UnitService.orderConversion(c,l.product,l.unit,l.quantity,nullableId(dealer.get("warehouse_id")));}catch(IllegalArgumentException e){errors.put("line"+i,e.getMessage());}}
            if(!errors.isEmpty())throw new FieldValidationException(errors);
            long saved=id;if(id==0){try{saved=Sql.insert(c,"INSERT INTO orders(dealer_id,address_id,desired_delivery,created_by,owner_id,creation_key,creation_hash) VALUES(?,?,?,?,?,?,?)",in.dealer,in.address,in.delivery,actor,actor,key,hash);}catch(SQLException e){if(e.getErrorCode()==1062)throw FieldValidationException.field("form","Mã lưu đã được dùng đồng thời. Hãy mở lại đơn đã lưu.");throw e;}}
            else{Sql.update(c,"UPDATE orders SET address_id=?,desired_delivery=?,version=version+1 WHERE id=?",in.address,in.delivery,id);Sql.update(c,"DELETE FROM order_lines WHERE order_id=?",id);Sql.update(c,"DELETE FROM product_transaction_references WHERE reference_type='ORDER' AND reference_id=?",Long.toString(id));Sql.update(c,"DELETE FROM order_quote_tickets WHERE order_id=?",id);}
            int position=0;for(var l:in.lines)Sql.insert(c,"INSERT INTO order_lines(order_id,position,product_id,unit_id,quantity) VALUES(?,?,?,?,?)",saved,position++,l.product,l.unit,CatalogValidation.decimal(l.quantity,6,true));
            Sql.update(c,"INSERT IGNORE INTO product_transaction_references(product_id,reference_type,reference_id) SELECT product_id,'ORDER',? FROM order_lines WHERE order_id=?",Long.toString(saved),saved);
            Sql.update(c,"INSERT IGNORE INTO dealer_transaction_references(dealer_id,reference_type,reference_id) VALUES(?,'ORDER',?)",in.dealer,Long.toString(saved));
            AuditService.record(c,actor,"ORDER_DRAFT_SAVED","ORDER",saved,before,Sql.one(c,"SELECT * FROM orders WHERE id=?",saved));return saved;});
    }
    public Map<String,Object> find(long actor,long id){return Sql.transaction(source,c->{var ctx=context(c,actor,id,false);var row=new LinkedHashMap<>(ctx.order);row.put("dealer_name",ctx.dealer.get("name"));row.put("dealer_code",ctx.dealer.get("code"));row.put("transaction_locked",DealerStatusService.locked(ctx.dealer));
        row.put("can_edit","DRAFT".equals(row.get("status"))&&Sql.id(row.get("owner_id"))==actor&&ctx.access.allows(portal(ctx.access)?"PORTAL_ORDER_WRITE":"ORDER_WRITE"));
        row.put("can_takeover","DRAFT".equals(row.get("status"))&&Sql.id(row.get("owner_id"))!=actor&&ctx.access.roles().contains("SALES_MANAGER")&&ctx.access.allows("ORDER_WRITE"));
        row.put("delivery_address",Sql.one(c,"SELECT address,recipient,phone,directions,status FROM dealer_addresses WHERE id=? AND dealer_id=?",row.get("address_id"),row.get("dealer_id")));
        row.put("lines",Sql.query(c,"SELECT l.*,p.sku,p.name,u.name unit_name FROM order_lines l JOIN products p ON p.id=l.product_id JOIN product_units u ON u.id=l.unit_id WHERE l.order_id=? ORDER BY l.position",id));
        if(portal(ctx.access)){row.keySet().removeAll(Set.of("created_by","owner_id","creation_key","creation_hash","submit_key","user_version"));row.put("can_takeover",false);}return row;});}
    public PageResult<Map<String,Object>> search(long actor,String query,String status,Long dealer,PageRequest request){String q=query==null?"":query.trim();if(q.length()>150)q=q.substring(0,150);String term="%"+q+"%";String state=Set.of("DRAFT","SUBMITTED").contains(status==null?"":status)?status:"";
        return Sql.transaction(source,c->{var a=reader(c,actor);boolean portal=portal(a);String scope=portal?" d.id=? ":" (? OR d.primary_staff_id=?) ";String filter=" FROM orders o JOIN dealers d ON d.id=o.dealer_id WHERE "+scope+" AND (?='' OR o.status=?) AND (? IS NULL OR o.dealer_id=?) AND (d.code LIKE ? OR d.name LIKE ? OR CAST(o.id AS CHAR) LIKE ?)";
            var args=new ArrayList<Object>();if(portal)args.add(PortalAccountService.linked(c,actor));else{args.add(all(a));args.add(actor);}args.addAll(Arrays.asList(state,state,dealer,dealer,term,term,term));long total=Sql.id(Sql.one(c,"SELECT COUNT(*) total"+filter,args.toArray()).get("total"));var p=request.clamp(total);var params=new ArrayList<Object>(args);params.add(p.pageSize());params.add(p.offset());
            var rows=Sql.query(c,"SELECT o.id,o.status,o.desired_delivery,o.net_total,o.version,o.created_at,d.code dealer_code,d.name dealer_name,d.transaction_locked"+filter+" ORDER BY o.id DESC LIMIT ? OFFSET ?",params.toArray());return new PageResult<>(rows,total,p.page(),p.pageSize());});}
    public List<Map<String,Object>> addresses(long actor,long dealer){return Sql.transaction(source,c->{var a=reader(c,actor);scoped(c,actor,a,dealer,false);return Sql.query(c,"SELECT id,address,recipient,phone,is_default FROM dealer_addresses WHERE dealer_id=? AND status='ACTIVE' ORDER BY is_default DESC,id",dealer);});}
    public List<Map<String,Object>> units(long actor,long dealer,long product){return Sql.transaction(source,c->{var a=reader(c,actor);var d=scoped(c,actor,a,dealer,false);if(portal(a))PortalCatalogService.requireOffer(c,actor,product);var rows=Sql.query(c,"SELECT id,name,factor,is_base FROM product_units WHERE product_id=? AND (warehouse_id IS NULL OR warehouse_id=?) ORDER BY is_base DESC,name,id",product,d.get("warehouse_id"));for(var row:rows)row.put("factor",((BigDecimal)row.get("factor")).toPlainString());return rows;});}
    public void takeover(long actor,long id,long version,String reason){String why=CatalogValidation.text(reason,1000,"Lý do tiếp quản");Sql.transaction(source,c->{var ctx=context(c,actor,id,true);writer(ctx.access);if(!ctx.access.roles().contains("SALES_MANAGER"))throw new SecurityException();editable(ctx.order,version);
        if(Sql.id(ctx.order.get("owner_id"))==actor)throw FieldValidationException.field("form","Bạn đang phụ trách nháp này.");
        Sql.update(c,"UPDATE orders SET owner_id=?,version=version+1 WHERE id=?",actor,id);Sql.update(c,"DELETE FROM order_quote_tickets WHERE order_id=?",id);
        var after=new LinkedHashMap<>(Sql.one(c,"SELECT * FROM orders WHERE id=?",id));after.put("reason",why);AuditService.record(c,actor,"ORDER_DRAFT_TAKEN_OVER","ORDER",id,ctx.order,after);return null;});}
    public Quote preview(long actor,Input in){validate(in);return Sql.transaction(source,c->{var a=reader(c,actor);writer(a);var dealer=scoped(c,actor,a,in.dealer,true);return calculate(c,dealer,in.address,in.delivery,in.lines);});}
    public Quote quote(long actor,long id){return Sql.transaction(source,c->{var ctx=context(c,actor,id,true);writer(ctx.access);owner(ctx.order,actor);editable(ctx.order,Sql.id(ctx.order.get("version")));
        var q=calculate(c,ctx.dealer,Sql.id(ctx.order.get("address_id")),LocalDate.parse(ctx.order.get("desired_delivery").toString()),inputs(c,id));return q.valid()?ticket(c,actor,id,Sql.id(ctx.order.get("version")),q,false):q;});}
    public Quote estimate(long actor,long id){return Sql.transaction(source,c->{var ctx=context(c,actor,id,true);if(!"DRAFT".equals(ctx.order.get("status")))throw new IllegalArgumentException("Đơn đã gửi dùng số tiền snapshot.");return calculate(c,ctx.dealer,Sql.id(ctx.order.get("address_id")),LocalDate.parse(ctx.order.get("desired_delivery").toString()),inputs(c,id));});}
    private Quote ticket(Connection c,long actor,long id,long version,Quote q,boolean confirmation)throws SQLException{String token=UUID.randomUUID().toString();
        Sql.update(c,"DELETE FROM order_quote_tickets WHERE order_id=? AND expires_at<?",id,Timestamp.from(clock.instant()));
        Sql.update(c,"INSERT INTO order_quote_tickets(token,order_id,actor_id,order_version,digest,requires_confirmation,expires_at) VALUES(?,?,?,?,?,?,?)",token,id,actor,version,q.digest,confirmation,Timestamp.from(clock.instant().plusSeconds(900)));
        return new Quote(q.date,q.lines,q.gross,q.discount,q.net,q.errors,q.warning,q.digest,token,confirmation);}
    private Quote calculate(Connection c,Map<String,Object> dealer,long address,LocalDate delivery,List<LineInput> input)throws SQLException{
        LocalDate date=clock.instant().atZone(VietnamTime.ZONE).toLocalDate();var errors=new LinkedHashMap<String,String>();var signature=new ArrayList<Object>();signature.add(date);signature.add(dealer);signature.add(delivery);
        try{signature.add(address(c,dealer,address));}catch(IllegalArgumentException e){errors.put("address",e.getMessage());}
        Long warehouse=nullableId(dealer.get("warehouse_id"));long group=Sql.id(dealer.get("group_id"));if(warehouse==null)errors.put("warehouse","Cần kho phục vụ trước khi gửi. Nháp vẫn được giữ.");
        signature.add(Sql.one(c,"SELECT id,name FROM customer_groups WHERE id=? FOR SHARE",group));if(warehouse!=null)signature.add(Sql.one(c,"SELECT id,name FROM warehouses WHERE id=? FOR SHARE",warehouse));
        productLocks(c,input);var conversions=new TreeMap<Integer,UnitService.Conversion>();var products=new HashMap<Long,Map<String,Object>>();var prices=new HashMap<Long,PricingService.Quote>();var quantities=new TreeMap<Long,List<BigDecimal>>();
        for(int i=0;i<input.size();i++){var l=input.get(i);try{var p=Sql.one(c,"SELECT id,sku,name,status,version FROM products WHERE id=?",l.product);if(!"ACTIVE".equals(p.get("status")))throw new IllegalArgumentException("Sản phẩm đã ngừng bán.");products.put(l.product,p);signature.add(p);
                var conversion=UnitService.orderConversion(c,l.product,l.unit,l.quantity,warehouse);conversions.put(i,conversion);quantities.computeIfAbsent(l.product,k->new ArrayList<>()).add(conversion.baseQuantity());signature.add(conversion);
                if(!prices.containsKey(l.product)){PricingService.Quote price;try{price=PricingService.orderQuote(c,group,l.product,date);}catch(IllegalArgumentException e){throw new IllegalArgumentException("Chưa có giá áp dụng cho SKU trong ngày gửi tại Việt Nam.");}prices.put(l.product,price);signature.add(price);}
            }catch(IllegalArgumentException e){errors.put("line"+i,e.getMessage());}}
        var policies=new HashMap<Long,DiscountMath.Selection>();for(var item:quantities.entrySet())if(prices.containsKey(item.getKey()))try{var choice=DiscountService.select(c,item.getKey(),group,date,item.getValue(),prices.get(item.getKey()).sellingPrice());policies.put(item.getKey(),choice);signature.add(choice);}catch(IllegalArgumentException e){for(int i=0;i<input.size();i++)if(input.get(i).product==item.getKey())errors.put("line"+i,e.getMessage());}
        var lines=new ArrayList<LineQuote>();BigDecimal gross=BigDecimal.ZERO.setScale(4),discount=gross,net=gross;
        for(int i=0;i<input.size();i++){if(errors.containsKey("line"+i))continue;var l=input.get(i);var conv=conversions.get(i);var price=prices.get(l.product);var choice=policies.get(l.product);if(conv==null||price==null||choice==null)continue;
            try{var g=DiscountMath.lineGross(conv.baseQuantity(),price.sellingPrice());var d=DiscountMath.lineDiscount(conv.baseQuantity(),price.sellingPrice(),choice.tier());var n=DiscountMath.money(g.subtract(d));
                // Reject effective below-floor discounts even when a tiny quantity rounds to zero.
                BigDecimal effective=price.sellingPrice();if(choice.applied()){var t=choice.tier();effective="FIXED".equals(t.mode())?effective.subtract(t.value()):effective.multiply(BigDecimal.ONE.subtract(t.value().divide(new BigDecimal("100"))));}
                if(effective.compareTo(price.floorPrice())<0||n.compareTo(DiscountMath.money(conv.baseQuantity().multiply(price.floorPrice())))<0)throw new IllegalArgumentException("Giá sau chiết khấu không đạt điều kiện giá sàn; không thể gửi.");
                var p=products.get(l.product);lines.add(new LineQuote(i,l.product,Sql.text(p.get("sku")),Sql.text(p.get("name")),conv,price,choice,g,d,n));
                gross=DiscountMath.money(gross.add(g));discount=DiscountMath.money(discount.add(d));net=DiscountMath.money(net.add(n));
            }catch(IllegalArgumentException e){errors.put("line"+i,e.getMessage());}}
        String warning=DealerStatusService.locked(dealer)?"Đại lý hiện bị khóa: chỉ xử lý đơn đã tồn tại, không tạo đơn mới.":!"ACTIVE".equals(dealer.get("status"))?"Đại lý đã ngừng giao dịch; đây là đơn đã tồn tại.":"";
        signature.add(lines);return new Quote(date,List.copyOf(lines),gross,discount,net,Collections.unmodifiableMap(errors),warning,digest(signature),"",false);
    }
    public Submission submit(long actor,long id,long version,String token,String submitKey,boolean confirmed){String key=uuid(submitKey,"submitKey");
        return Sql.transaction(source,c->{var ctx=context(c,actor,id,true);writer(ctx.access);owner(ctx.order,actor);
            if("SUBMITTED".equals(ctx.order.get("status")))return new Submission(true,id,null);
            if(!Sql.query(c,"SELECT id FROM orders WHERE owner_id=? AND submit_key=? AND id<>?",actor,key,id).isEmpty())throw FieldValidationException.field("submitKey","Mã gửi đã dùng cho đơn khác. Hãy tải lại đơn.");
            editable(ctx.order,version);var q=calculate(c,ctx.dealer,Sql.id(ctx.order.get("address_id")),LocalDate.parse(ctx.order.get("desired_delivery").toString()),inputs(c,id));if(!q.valid())return new Submission(false,id,q);
            var tickets=Sql.query(c,"SELECT * FROM order_quote_tickets WHERE token=? AND actor_id=? AND order_id=? AND order_version=?",uuid(token,"quote"),actor,id,version);
            if(tickets.isEmpty()||!q.digest.equals(tickets.get(0).get("digest"))||!Sql.instant(tickets.get(0).get("expires_at")).isAfter(clock.instant()))return new Submission(false,id,ticket(c,actor,id,version,q,true));
            if(DealerAddressService.isDefault(tickets.get(0).get("requires_confirmation"))&&!confirmed)return new Submission(false,id,ticket(c,actor,id,version,q,true));
            var dealer=ctx.dealer;var addr=address(c,dealer,Sql.id(ctx.order.get("address_id")));long group=Sql.id(dealer.get("group_id")),warehouse=Sql.id(dealer.get("warehouse_id"));
            try{Sql.update(c,"UPDATE orders SET status='SUBMITTED',version=version+1,submit_key=?,submitted_at=?,pricing_date=?,group_id=?,warehouse_id=?,dealer_code_snapshot=?,dealer_name_snapshot=?,address_snapshot=?,recipient_snapshot=?,phone_snapshot=?,directions_snapshot=?,group_name_snapshot=?,warehouse_name_snapshot=?,gross_total=?,discount_total=?,net_total=? WHERE id=?",key,Timestamp.from(clock.instant()),q.date,group,warehouse,dealer.get("code"),dealer.get("name"),addr.get("address"),addr.get("recipient"),addr.get("phone"),addr.get("directions"),Sql.one(c,"SELECT name FROM customer_groups WHERE id=?",group).get("name"),Sql.one(c,"SELECT name FROM warehouses WHERE id=?",warehouse).get("name"),q.gross,q.discount,q.net,id);}catch(SQLException e){if(e.getErrorCode()==1062)throw FieldValidationException.field("submitKey","Mã gửi đã dùng đồng thời cho đơn khác. Hãy tải lại đơn.");throw e;}
            String reference="ORDER:"+id;var seen=new HashSet<Long>();
            for(var l:q.lines){var cv=l.conversion;Sql.update(c,"UPDATE order_lines SET sku_snapshot=?,name_snapshot=?,unit_name_snapshot=?,factor_snapshot=?,unit_version=?,base_quantity=?,selling_price=?,gross=?,discount=?,net=?,policy_name_snapshot=? WHERE order_id=? AND position=?",l.sku,l.name,cv.unitName(),cv.factor(),cv.version(),cv.baseQuantity(),l.price.sellingPrice(),l.gross,l.discount,l.net,l.policy.applied()?l.policy.tier().name():null,id,l.position);
                UnitService.snapshot(c,reference+":"+l.position,cv);if(seen.add(l.product)){PricingService.snapshot(c,reference,l.price);DiscountService.snapshot(c,reference,l.product,l.policy);}}
            AuditService.record(c,actor,"ORDER_SUBMITTED","ORDER",id,ctx.order,Sql.one(c,"SELECT * FROM orders WHERE id=?",id));Sql.update(c,"DELETE FROM order_quote_tickets WHERE order_id=?",id);return new Submission(true,id,null);
        });
    }
}
