package vn.codegym.salesinventory.validation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

class ProfileValidatorTest {
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings={" ","A"})
    void rejectsShortNames(String name) {
        assertThat(ProfileValidator.validate(name,"0901234567")).containsKey("fullName").doesNotContainKey("phone");
    }

    @Test void acceptsNameBoundariesAndVietnamesePunctuation() {
        for(String name:new String[]{"AB","A".repeat(150),"  Nguyễn O'Đô  "}) {
            assertThat(ProfileValidator.validate(name,"0901234567")).isEmpty();
        }
        assertThat(ProfileValidator.validate("A".repeat(151),"0901234567")).containsKey("fullName");
    }

    @ParameterizedTest
    @ValueSource(strings={"0301234567","0501234567","0701234567","0801234567","0901234567",
            "+84 901-234-567","(024) 1234.5678","+84 24 1234 5678"})
    void acceptsVietnameseMobileAndLandline(String phone) {
        assertThat(ProfileValidator.validate("Nguyễn Việt Đô",phone)).isEmpty();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings={" ","abc0901234567","0123456789","09012","09012345678","+1 901234567","+840901234567"})
    void rejectsInvalidPhones(String phone) {
        assertThat(ProfileValidator.validate("Nguyễn Việt Đô",phone)).containsKey("phone").doesNotContainKey("fullName");
    }

    @Test void reportsBothErrorsAndProvidesImmutableExceptionDetails() {
        var exception=new ProfileValidationException(ProfileValidator.validate(" ","bad"));
        assertThat(exception.errors()).containsKeys("fullName","phone");
        assertThatThrownBy(()->exception.errors().clear()).isInstanceOf(UnsupportedOperationException.class);
    }
}
