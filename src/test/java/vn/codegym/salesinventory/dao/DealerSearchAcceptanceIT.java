package vn.codegym.salesinventory.dao;
import java.util.*;
import org.junit.jupiter.api.Test;
import vn.codegym.salesinventory.dto.PageRequest;
import vn.codegym.salesinventory.service.DealerService;
import static org.assertj.core.api.Assertions.*;
class DealerSearchAcceptanceIT extends StoryDatabaseSupport {
    @Test void filtersAndSuggestionsUseSameScopeAndAccentInsensitiveNames(){long manager=user("SALES_MANAGER"),sales=user("SALES"),other=user("SALES"),group=Sql.id(one("SELECT id FROM customer_groups LIMIT 1").get("id"));long territory=insert("INSERT INTO territories(code,name) VALUES(?,'Khu vực thử')","T-"+UUID.randomUUID());var service=new DealerService(source);String prefix="SEARCH-"+UUID.randomUUID();for(int i=0;i<21;i++)service.save(manager,0,new DealerService.Input(prefix+"-"+i,"Cà phê thử "+prefix,"","0912345678",group,territory,i<20?sales:other,null,"ACTIVE",0));
        var filter=new DealerService.Filter("ca phe",territory,group,sales,"ACTIVE");var first=service.search(sales,filter,new PageRequest(1,20));assertThat(first.totalItems()).isEqualTo(20);assertThat(first.items()).hasSize(20);
        assertThat(service.search(other,filter,new PageRequest(1,20)).totalItems()).isZero();assertThat(service.search(sales,new DealerService.Filter(prefix,territory,group,other,""),new PageRequest(1,20)).items()).isEmpty();
        assertThat(service.search(manager,new DealerService.Filter(prefix,territory,group,null,"DISCONTINUED"),new PageRequest(1,20)).totalItems()).isZero();
        assertThat(service.suggest(sales,prefix)).hasSize(10).allSatisfy(row->assertThat(row).containsOnlyKeys("id","code","name"));assertThat(service.suggest(sales,prefix+"-20")).isEmpty();assertThat(service.suggest(other,prefix+"-20")).hasSize(1);
        assertThat(service.filterOptions(sales).get("filterStaff")).hasSize(1).first().satisfies(row->assertThat(Sql.id(row.get("id"))).isEqualTo(sales));assertThat(service.suggest(sales,"c")).isEmpty();
    }
}
