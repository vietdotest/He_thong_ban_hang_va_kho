package vn.codegym.salesinventory.dto;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class PageRequestTest {
    @Test void malformedAndOversizedInputsFallBackWithoutOverflow() {
        assertThat(PageRequest.parse("9999999999999999","5000")).isEqualTo(new PageRequest(1,20));
        assertThat(PageRequest.parse("-2","50")).isEqualTo(new PageRequest(1,50));
        assertThat(new PageRequest(Integer.MAX_VALUE,100).offset()).isEqualTo(214748364600L);
    }
    @Test void lastPageAndWindowStayBoundedForFiveThousandRows() {
        var effective=new PageRequest(999,20).clamp(5000);
        assertThat(effective.page()).isEqualTo(250);
        var page=new PageResult<>(List.of("last"),5000,250,20);
        assertThat(page.getWindowEnd()-page.getWindowStart()).isEqualTo(4);
        assertThat(page.getFirstItem()).isEqualTo(4981);
        assertThat(new PageRequest(99,100).clamp(0).page()).isEqualTo(1);
    }
}
