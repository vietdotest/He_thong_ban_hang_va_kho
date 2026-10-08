package vn.codegym.salesinventory.dao;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import vn.codegym.salesinventory.dto.PageRequest;
import vn.codegym.salesinventory.service.*;
import static org.assertj.core.api.Assertions.*;

class PagingLookupAcceptanceIT extends StoryDatabaseSupport {
    @Test void pagingClampsLastPageAndCountMatchesFilteredRows() {
        long manager=user("SALES_MANAGER"),category=category(manager);
        String prefix="PAGE-"+UUID.randomUUID();
        for(int i=0;i<21;i++)insert("INSERT INTO products(sku,name,category_id,base_unit) VALUES(?, 'Cà phê', ?, 'Lon')",prefix+String.format("-%03d",i),category);
        var service=new ProductService(source);
        var first=service.search(manager,prefix,category,"ACTIVE",new PageRequest(1,20));
        assertThat(first.totalItems()).isEqualTo(21);assertThat(first.items()).hasSize(20);
        var last=service.search(manager,prefix,category,"ACTIVE",new PageRequest(Integer.MAX_VALUE,20));
        assertThat(last.page()).isEqualTo(2);assertThat(last.items()).hasSize(1);
        assertThat(last.items().get(0).get("sku")).isEqualTo(prefix+"-020");
        assertThat(service.search(manager,prefix,category,"DISCONTINUED",new PageRequest(1,100)).totalItems()).isZero();
        assertThat(first.items().toString()).contains("cost_price");
        assertThat(service.search(user("SALES"),prefix,category,"",new PageRequest(1,50)).items().toString()).doesNotContain("cost_price");
    }
    @Test void suggestionsAreAccentInsensitiveBoundedAndEnforcePermissionBeforeEmptyQuery() {
        long manager=user("SALES_MANAGER"),category=category(manager);
        String prefix="LOOK-"+UUID.randomUUID();
        for(int i=0;i<21;i++)insert("INSERT INTO products(sku,name,category_id,base_unit) VALUES(?, 'Cà phê thử', ?, 'Lon')",prefix+"-"+i,category);
        var lookup=new LookupService(source);
        assertThat(lookup.search(manager,"products","ca phe")).hasSize(10).allSatisfy(r->assertThat(r).containsOnlyKeys("id","code","name","detail"));
        assertThat(lookup.search(manager,"products",prefix+"-20")).first().satisfies(r->assertThat(r.get("code")).isEqualTo(prefix+"-20"));
        assertThat(lookup.search(manager,"products","c")).isEmpty();
        assertThatThrownBy(()->lookup.search(manager,"users","")).isInstanceOf(SecurityException.class);
        assertThatThrownBy(()->lookup.search(manager,"unknown","ca")).isInstanceOf(IllegalArgumentException.class);
    }
}
