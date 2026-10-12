package vn.codegym.salesinventory.validation;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class TaxCodeTest {
    @Test void emptyTaxCodeAndBranchFormatsAreCanonical() {
        assertThat(TaxCode.normalize(" ")).isEmpty();
        assertThat(TaxCode.normalize("0101234567")).isEqualTo("0101234567");
        assertThat(TaxCode.normalize("0101234567001")).isEqualTo("0101234567-001");
        assertThat(TaxCode.normalize("0101234567-001")).isEqualTo("0101234567-001");
    }
    @Test void malformedTaxCodesAreRejected() {
        for(String bad:new String[]{"123","010123456x","0101234567--001","0101234567 001"})assertThatThrownBy(()->TaxCode.normalize(bad)).isInstanceOf(IllegalArgumentException.class);
    }
}
