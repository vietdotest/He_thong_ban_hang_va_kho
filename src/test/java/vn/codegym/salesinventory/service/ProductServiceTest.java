package vn.codegym.salesinventory.service;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import vn.codegym.salesinventory.security.Access;
import vn.codegym.salesinventory.validation.ProductValidationException;
import static org.assertj.core.api.Assertions.*;

class ProductServiceTest {
    private ProductService.Input input(String sku, String name, String base, String pack, BigDecimal cost) {
        return new ProductService.Input(sku, name, 1, base, pack, cost, null, "ACTIVE", 0);
    }
    @Test void allInvalidFieldsAreReportedTogether() {
        var in = new ProductService.Input(null, " ", 0, null, "a".repeat(251), new BigDecimal("-1"), "../file", "WRONG", -1);
        assertThat(ProductService.errors(in)).containsKeys("sku", "name", "category", "baseUnit", "packaging", "cost", "image", "status", "form");
        assertThatThrownBy(() -> ProductService.validate(in)).isInstanceOf(ProductValidationException.class);
    }
    @ParameterizedTest @ValueSource(ints = {1, 64}) void skuAcceptedAtValidLengths(int length) {
        assertThat(ProductService.errors(input("A".repeat(length), "Tên Việt O'Neil", "Lon", "", null))).isEmpty();
    }
    @ParameterizedTest @ValueSource(strings = {"", " ", "SKU 1", "<script>", "ĐƠN-VỊ"}) void invalidSkuRejected(String sku) {
        assertThat(ProductService.errors(input(sku, "Tên", "Lon", "", null))).containsKey("sku");
    }
    @Test void skuOverBoundaryRejected() {
        assertThat(ProductService.errors(input("A".repeat(65), "Tên", "Lon", "", null))).containsKey("sku");
    }
    @ParameterizedTest @ValueSource(ints = {1, 200}) void namesAcceptedAtValidLengths(int length) {
        assertThat(ProductService.errors(input("SKU", "a".repeat(length), "L", "", null))).isEmpty();
    }
    @ParameterizedTest @ValueSource(strings = {"", "   "}) void blankNameAndBaseUnitRejected(String text) {
        assertThat(ProductService.errors(input("SKU", text, text, "", null))).containsKeys("name", "baseUnit");
    }
    @Test void textLengthBoundaries() {
        assertThat(ProductService.errors(input("SKU", "a".repeat(200), "b".repeat(50), "c".repeat(250), null))).isEmpty();
        assertThat(ProductService.errors(input("SKU", "a".repeat(201), "b".repeat(51), "c".repeat(251), null))).containsKeys("name", "baseUnit", "packaging");
    }
    @ParameterizedTest @ValueSource(strings = {"0", "999999999999999.9999", "1.23000"}) void validDecimalBoundaries(String value) {
        assertThat(ProductService.errors(input("SKU", "Tên", "Lon", "", new BigDecimal(value)))).isEmpty();
    }
    @ParameterizedTest @ValueSource(strings = {"-0.0001", "0.00001", "1000000000000000", "1E999999999", "1E-999999999"}) void invalidCostBoundaries(String value) {
        assertThat(ProductService.errors(input("SKU", "Tên", "Lon", "", new BigDecimal(value)))).containsKey("cost");
    }
    @Test void columnsHideCostUnlessRoleAndPermissionBothAllowIt() {
        for (String role : List.of("ADMIN", "SALES", "WAREHOUSE", "WAREHOUSE_MANAGER", "ACCOUNTANT", "DIRECTOR")) {
            var a = new Access(Set.of(role), Set.of("COST_READ"), List.of(), List.of(), List.of());
            assertThat(ProductService.columns(a)).doesNotContain("cost_price");
        }
        assertThat(ProductService.columns(new Access(Set.of("SALES_MANAGER"), Set.of("COST_READ"), List.of(), List.of(), List.of()))).contains("cost_price");
        assertThat(ProductService.columns(new Access(Set.of("SALES_MANAGER"), Set.of(), List.of(), List.of(), List.of()))).doesNotContain("cost_price");
    }
    @Test void onlySkuUniqueViolationIsClassifiedAsInputError() {
        assertThat(ProductService.isDuplicateSku(new SQLException("Duplicate entry 'ABC' for key 'products.sku'", "23000", 1062))).isTrue();
        assertThat(ProductService.isDuplicateSku(new SQLException("Duplicate entry 'ABC' for key 'sku'", "23000", 1062))).isTrue();
        assertThat(ProductService.isDuplicateSku(new SQLException("Duplicate entry for key 'phone_normalized'", "23000", 1062))).isFalse();
        assertThat(ProductService.isDuplicateSku(new SQLException("Unknown column sku", "42S22", 1054))).isFalse();
        assertThat(ProductService.isDuplicateSku(new SQLException("Deadlock", "40001", 1213))).isFalse();
    }
}
