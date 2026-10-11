package vn.codegym.salesinventory.service;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.math.BigDecimal;
import static org.assertj.core.api.Assertions.*;
class AuditServiceTest {
    @Test void secretsAreNeverStoredAndCostHasASeparateSnapshot() {
        var map=Map.of("name","Lon nước","cost_price",new BigDecimal("12500.25"),"password_hash","secret","token_hash","token");
        assertThat(AuditService.snapshot(map,false)).contains("Lon nước").doesNotContain("12500","cost_price","secret","token");
        assertThat(AuditService.snapshot(map,true)).contains("12500.25").doesNotContain("secret","token");
    }
    @Test void snapshotEscapesUserControlledJsonAndSupportsNullBeforeCreate() { assertThat(AuditService.snapshot(Map.of("name","\"x\"\n"),false)).isEqualTo("{\"name\":\"\\\"x\\\"\\n\"}");assertThat(AuditService.snapshot(null,false)).isNull(); }
    @Test void nestedSecretsAndCaseVariantsNeverLeak() {
        var values=Map.of("PASSWORD","secret-one","TOKEN","secret-two","nested",Map.of("password_hash","secret-three","cost_price",123,"name","Tên"),"items",List.of(Map.of("csrf_token","secret-four","name","Khác")));
        assertThat(AuditService.snapshot(values,false)).contains("Tên","Khác").doesNotContain("secret","123","cost_price");
        assertThat(AuditService.snapshot(values,true)).doesNotContain("secret");
    }
    @Test void escapesAllJsonControlCharactersWithoutInterpretingHtml() {
        assertThat(AuditService.snapshot(Map.of("name","<script>\t\r\u0001\\"),false)).contains("<script>","\\t","\\r","\\u0001","\\\\");
    }
}
