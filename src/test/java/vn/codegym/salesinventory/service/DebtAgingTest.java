package vn.codegym.salesinventory.service;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class DebtAgingTest {
    @Test void boundariesUseDueDateRatherThanInvoiceDate() {
        LocalDate today=LocalDate.of(2027,1,3);
        int[] days={-1,0,1,30,31,60,61,90,91};
        String[] keys={"not_due","due_today","days_1_30","days_1_30","days_31_60","days_31_60","days_61_90","days_61_90","days_over_90"};
        for(int i=0;i<days.length;i++)assertThat(DebtAging.bucket(today.minusDays(days[i]),today)).isEqualTo(keys[i]);
    }
}
