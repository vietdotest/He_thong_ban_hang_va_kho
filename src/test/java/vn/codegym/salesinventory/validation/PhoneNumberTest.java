package vn.codegym.salesinventory.validation;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class PhoneNumberTest {
    @Test void normalizesInternationalAndDomesticVietnameseNumbers() { assertThat(PhoneNumber.normalize("+84 901-234-567")).isEqualTo("0901234567");assertThat(PhoneNumber.normalize("02412345678")).isEqualTo("02412345678"); }
    @Test void rejectsLettersWrongLengthAndUnassignedMobilePrefix() { for(String bad:new String[]{"abc0901234567","0123456789","09012","+1 901234567"})assertThatThrownBy(() -> PhoneNumber.normalize(bad)).isInstanceOf(IllegalArgumentException.class); }
}
