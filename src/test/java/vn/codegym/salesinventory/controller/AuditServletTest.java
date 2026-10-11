package vn.codegym.salesinventory.controller;
import java.sql.Timestamp;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class AuditServletTest {
    @Test void combinedFilterUsesBoundParametersAndVietnamInclusiveDates() {
        var filter=AuditServlet.filter("12","PRODUCT' OR 1=1 --","2026-10-02","2026-10-02","2");
        assertThat(filter.sql()).doesNotContain("OR 1=1");assertThat(filter.args()).containsExactly(12L,"PRODUCT' OR 1=1 --",Timestamp.from(Instant.parse("2026-10-01T17:00:00Z")),Timestamp.from(Instant.parse("2026-10-02T17:00:00Z")));assertThat(filter.page()).isEqualTo(2);
    }
    @Test void emptyFiltersSelectFirstPage() {var filter=AuditServlet.filter("","","","","");assertThat(filter.args()).isEmpty();assertThat(filter.page()).isEqualTo(1);}
    @Test void rejectsInvalidDatesInvertedRangeAndForgedActor() {
        assertThatThrownBy(()->AuditServlet.filter("","","2026-02-30","","")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->AuditServlet.filter("","","2026-10-03","2026-10-02","")).isInstanceOf(IllegalArgumentException.class);
        for(String actor:new String[]{"-1","0","<script>"})assertThatThrownBy(()->AuditServlet.filter(actor,"","","","")).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void pageOverflowIsBusinessErrorNotArithmeticSystemError() {
        for(String page:new String[]{"0","-1","100001","9223372036854775807","bad"})assertThatThrownBy(()->AuditServlet.filter("","","","",page)).isInstanceOf(IllegalArgumentException.class);
        assertThat(AuditServlet.filter("","","","","100000").page()).isEqualTo(100000);
    }
}
