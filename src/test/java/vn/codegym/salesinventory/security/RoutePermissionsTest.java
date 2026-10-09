package vn.codegym.salesinventory.security;

import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class RoutePermissionsTest {
    private static final Map<String,String> LOOKUPS=Map.ofEntries(
        Map.entry("products","CATALOG_READ"),Map.entry("categories","CATALOG_READ"),
        Map.entry("suppliers","CATALOG_READ"),Map.entry("unitnames","CATALOG_READ"),
        Map.entry("unitwarehouses","WAREHOUSE_MANAGE"),Map.entry("priceversions","PRICE_READ"),
        Map.entry("auditusers","AUDIT_READ"),Map.entry("scopes","USER_MANAGE"),
        Map.entry("users","USER_MANAGE"),Map.entry("warehouses","USER_MANAGE"),
        Map.entry("territories","USER_MANAGE"),Map.entry("dealers","DEALER_READ"));
    @Test void eachAutocompleteRouteUsesItsOwnBusinessPermission() {
        LOOKUPS.forEach((name,permission)->assertThat(RoutePermissions.required("/api/lookups/"+name,"GET")).as(name).isEqualTo(permission));
        assertThat(RoutePermissions.required("/api/lookups/unitwarehouses","GET")).isNotEqualTo("USER_MANAGE");
    }
    @Test void unknownLookupAndWriteMethodsRemainDenied() {
        assertThat(RoutePermissions.required("/api/lookups/unknown","GET")).isNull();
        LOOKUPS.forEach((name,permission)->{
            assertThat(RoutePermissions.required("/api/lookups/"+name,"POST")).isNull();
            assertThat(RoutePermissions.required("/api/lookups/"+name,"DELETE")).isNull();
        });
    }
}
