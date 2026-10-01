package vn.codegym.salesinventory.security;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
class AccessTest {
    private Access user(Set<String> roles,Set<String> permissions) { return new Access(roles,permissions,List.of(),List.of(),List.of()); }
    @Test void evenAdministratorCannotReadCostWithoutSalesManagerRole() {
        assertThat(user(Set.of("ADMIN"),Set.of("COST_READ","COST_WRITE")).allows("COST_READ")).isFalse();
        assertThat(user(Set.of("WAREHOUSE"),Set.of("COST_READ")).allows("COST_READ")).isFalse();
        assertThat(user(Set.of("SALES_MANAGER"),Set.of("COST_READ")).allows("COST_READ")).isTrue();
    }
    @Test void missingPermissionsAreDeniedAndMultipleRolesDoNotBypassSensitiveGuard() {
        var both=user(Set.of("ADMIN","WAREHOUSE"),Set.of("USER_MANAGE","COST_WRITE"));
        assertThat(both.allows("USER_MANAGE")).isTrue();
        assertThatThrownBy(() -> both.require("COST_WRITE")).isInstanceOf(SecurityException.class);
        assertThat(user(Set.of("SALES_MANAGER"),Set.of()).allows("COST_READ")).isFalse();
    }
    @Test void unknownRoutesAndWritesCannotUseReadOnlyPermissions() {
        assertThat(RoutePermissions.required("/catalog/products","POST")).isEqualTo("PRODUCT_MANAGE");
        assertThat(RoutePermissions.required("/catalog/products","GET")).isEqualTo("CATALOG_READ");
        assertThat(RoutePermissions.required("/unregistered-admin-operation","POST")).isNull();
        assertThat(RoutePermissions.publicPath("/admin/roles")).isFalse();
    }
}
