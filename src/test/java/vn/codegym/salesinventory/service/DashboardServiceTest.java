package vn.codegym.salesinventory.service;
import org.junit.jupiter.api.Test;
import java.util.*;
import vn.codegym.salesinventory.security.Access;
import static org.assertj.core.api.Assertions.*;
class DashboardServiceTest {
    @Test void salesCannotNavigateToWarehouseOrAdministration() {
        var a=new Access(Set.of("SALES"),Set.of("CATALOG_READ","PRICE_READ"),List.of(),List.of(),List.of());
        assertThat(DashboardService.areas(a)).extracting(DashboardService.WorkArea::path).containsExactly("/catalog/products","/pricing/lists");
    }
    @Test void multipleRolesCombineAllowedWorkWithoutInventingPermissions() {
        var a=new Access(Set.of("WAREHOUSE","SALES_MANAGER"),Set.of("WAREHOUSE_MANAGE","PRODUCT_MANAGE"),List.of(),List.of(),List.of());
        assertThat(DashboardService.areas(a)).hasSize(3);
        assertThat(DashboardService.heading(a)).contains("các vai trò");
    }
}
