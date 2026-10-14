package vn.codegym.salesinventory.dao;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;
import vn.codegym.salesinventory.service.*;
import vn.codegym.salesinventory.validation.CommandConflictException;

abstract class MvpWorkflowSupport extends StoryDatabaseSupport {
    long manager,sales,worker,warehouse,supplier,product,unit,dealer,address,group,priceVersion;
    final Instant now=Instant.parse("2027-01-03T03:00:00Z");final Clock clock=Clock.fixed(now,ZoneOffset.UTC);
    StockService stock;OrderService orders;FulfillmentService fulfillment;
    @BeforeEach void setup(){manager=user("SALES_MANAGER");sales=user("SALES");worker=user("WAREHOUSE");warehouse=warehouse(worker);
        supplier=insert("INSERT INTO suppliers(code,name,tax_code,contact_name,contact_phone,payment_terms,warehouse_id) VALUES(?,'Nhà cung cấp','','Người giao','0912345678','',?)","S-"+UUID.randomUUID(),warehouse);
        product=product(manager);unit=Sql.id(one("SELECT id FROM product_units WHERE product_id=? AND is_base=1",product).get("id"));
        group=insert("INSERT INTO customer_groups(code,name) VALUES(?,'Nhóm bán')","G-"+UUID.randomUUID().toString().substring(0,12));long territory=insert("INSERT INTO territories(code,name,address) VALUES(?,'Khu vực','Địa chỉ')","T-"+UUID.randomUUID());
        update("INSERT INTO user_territories(user_id,territory_id) VALUES(?,?)",sales,territory);dealer=new DealerService(source).save(manager,0,new DealerService.Input("D-"+UUID.randomUUID(),"Đại lý","","0912345678",group,territory,sales,warehouse,"ACTIVE",0));
        address=new DealerAddressService(source).save(manager,dealer,0,new DealerAddressService.Input("12 Đường giao","Người nhận","0912345678","",true,"ACTIVE",0,1));update("UPDATE dealers SET credit_limit=2000000,debt_days=30 WHERE id=?",dealer);
        var pricing=new PricingService(source);priceVersion=pricing.create(manager,group,"Giá",LocalDate.of(2027,1,1),LocalDate.of(2027,1,31),null);pricing.saveItem(manager,priceVersion,product,new BigDecimal("100000"),new BigDecimal("50000"));
        stock=new StockService(source,clock);orders=new OrderService(source,clock);fulfillment=new FulfillmentService(source,clock);receive("10",LocalDate.of(2027,1,1));receive("10",LocalDate.of(2027,1,2));
    }
    String key(){return UUID.randomUUID().toString();}
    void receive(String qty,LocalDate date){long id=stock.saveReceipt(worker,0,0,key(),new StockService.ReceiptInput(warehouse,supplier,"PN-"+key(),date,List.of(new StockService.ReceiptLine(product,unit,new BigDecimal(qty),"Lô cùng tên",null))));stock.confirmReceipt(worker,id,1,key());}
    long accepted(String qty){long id=orders.save(sales,0,new OrderService.Input(dealer,address,LocalDate.of(2027,1,4),0,key(),List.of(new OrderService.LineInput(product,unit,new BigDecimal(qty)))));var q=orders.quote(sales,id);assertThat(orders.submit(sales,id,1,q.token(),key(),false).submitted()).isTrue();return id;}
    long line(long order){return Sql.id(one("SELECT id FROM order_lines WHERE order_id=?",order).get("id"));}
    List<FulfillmentService.DispatchLine> take(long order,String qty){return List.of(new FulfillmentService.DispatchLine(line(order),new BigDecimal(qty)));}
    StockService.StockSnapshot balance(){return Sql.snapshot(source,c->StockService.snapshot(c,warehouse,product,now));}
    long dispatched(String ordered,String sent){long id=accepted(ordered);fulfillment.startPicking(worker,id,2,key());return fulfillment.dispatch(worker,id,3,key(),take(id,sent),"Xuất từng phần");}
    List<FulfillmentService.DeliveryLine> received(long shipment,String qty){long id=Sql.id(one("SELECT id FROM mvp_shipment_lines WHERE shipment_id=?",shipment).get("id"));return List.of(new FulfillmentService.DeliveryLine(id,new BigDecimal(qty)));}
    long warehouseManager(){long actor=user("WAREHOUSE_MANAGER");update("INSERT INTO user_warehouses(user_id,warehouse_id) VALUES(?,?)",actor,warehouse);return actor;}
    List<FulfillmentService.RecoveryLine> recovery(long shipment,String qty,String condition){long line=Sql.id(one("SELECT id FROM mvp_shipment_lines WHERE shipment_id=?",shipment).get("id"));return List.of(new FulfillmentService.RecoveryLine(line,new BigDecimal(qty),condition));}
}
