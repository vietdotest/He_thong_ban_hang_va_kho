package vn.codegym.salesinventory.dao;

import java.util.*;
import org.junit.jupiter.api.Test;
import vn.codegym.salesinventory.dto.PageRequest;
import vn.codegym.salesinventory.service.DealerService;
import static org.assertj.core.api.Assertions.*;

class DealerPointSummaryIT extends StoryDatabaseSupport {
    @Test void listAndProfileDistinguishEmptyActiveAndInactivePointsWithinScope() {
        long manager=user("SALES_MANAGER"),sales=user("SALES"),other=user("SALES");
        long territory=insert("INSERT INTO territories(code,name) VALUES(?,'Khu vực QA')","T-"+UUID.randomUUID());
        long group=Sql.id(one("SELECT id FROM customer_groups LIMIT 1").get("id"));
        String prefix="POINT-"+UUID.randomUUID();var dealers=new DealerService(source);
        long mine=dealers.save(manager,0,new DealerService.Input(prefix+"-A","Đại lý QA","","0912345678",group,territory,sales,null,"ACTIVE",0));
        long outside=dealers.save(manager,0,new DealerService.Input(prefix+"-B","Đại lý khác QA","","0912345679",group,territory,other,null,"ACTIVE",0));
        var empty=dealers.search(sales,prefix,new PageRequest(1,20));
        assertThat(empty.totalItems()).isEqualTo(1);
        assertThat(Sql.id(empty.items().get(0).get("total_addresses"))).isZero();
        insert("INSERT INTO dealer_addresses(dealer_id,address,recipient,phone,is_default,status) VALUES(?,'Điểm QA','Người nhận QA','0912345678',1,'ACTIVE')",mine);
        insert("INSERT INTO dealer_addresses(dealer_id,address,recipient,phone,is_default,status) VALUES(?,'Điểm cũ QA','Người nhận QA','0912345678',0,'DISCONTINUED')",mine);
        insert("INSERT INTO dealer_addresses(dealer_id,address,recipient,phone,is_default,status) VALUES(?,'Điểm khác QA','Người nhận QA','0912345679',1,'ACTIVE')",outside);
        var own=dealers.search(sales,prefix,new PageRequest(1,20));
        assertThat(own.totalItems()).isEqualTo(1);
        assertThat(Sql.id(own.items().get(0).get("active_addresses"))).isEqualTo(1);
        assertThat(Sql.id(own.items().get(0).get("total_addresses"))).isEqualTo(2);
        assertThat(Sql.id(dealers.find(sales,mine).get("active_addresses"))).isEqualTo(1);
        assertThatThrownBy(()->dealers.find(sales,outside)).isInstanceOf(SecurityException.class);
        assertThat(dealers.search(manager,prefix,new PageRequest(1,1)).totalItems()).isEqualTo(2);
    }
}
