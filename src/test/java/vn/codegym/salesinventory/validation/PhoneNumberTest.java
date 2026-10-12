package vn.codegym.salesinventory.validation;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class PhoneNumberTest {
    @Test void searchRequiresFiveDigitsAndNormalizesCountryCode() {
        assertThat(PhoneNumber.searchDigits("+84 987-65")).isEqualTo("098765");
        assertThat(PhoneNumber.searchDigits("98765")).isEqualTo("98765");
        for(String text:new String[]{"1","0987","qa-search-01","09+8765","+84"})assertThat(PhoneNumber.searchDigits(text)).isEmpty();
    }
    @Test void normalizesInternationalAndDomesticVietnameseNumbers() { assertThat(PhoneNumber.normalize("+84 901-234-567")).isEqualTo("0901234567");assertThat(PhoneNumber.normalize("02412345678")).isEqualTo("02412345678"); }
    @Test void rejectsLettersWrongLengthAndUnassignedMobilePrefix() { for(String bad:new String[]{"abc0901234567","0123456789","09012","+1 901234567"})assertThatThrownBy(() -> PhoneNumber.normalize(bad)).isInstanceOf(IllegalArgumentException.class); }
}
