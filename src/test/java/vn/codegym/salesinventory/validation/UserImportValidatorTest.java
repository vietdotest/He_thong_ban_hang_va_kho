package vn.codegym.salesinventory.validation;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import vn.codegym.salesinventory.model.UserStatus;
import static org.assertj.core.api.Assertions.*;

class UserImportValidatorTest {
    private List<String> row(String name, String phone) {
        return List.of("user-one", "one@test.local", name, phone, " sales, ACCOUNTANT, sales ", " K1, K1, K2 ", " T1 ");
    }

    @Test void normalizesPhonesRolesAndScopeCodesWithoutTrustingAccountStatus() {
        var input = UserImportValidator.validate(row(" Nguyễn Văn An ", "+84 912.345.678"));
        assertThat(input.command().phone()).isEqualTo("0912345678");
        assertThat(input.command().fullName()).isEqualTo("Nguyễn Văn An");
        assertThat(input.command().status()).isEqualTo(UserStatus.PENDING_ACTIVATION);
        assertThat(input.roles()).containsExactly("SALES", "ACCOUNTANT");
        assertThat(input.warehouses()).containsExactly("K1", "K2");
        assertThat(input.territories()).containsExactly("T1");
    }

    @ParameterizedTest @ValueSource(strings={"02412345678", "+84 (24) 1234-5678", "0351234567"})
    void acceptsVietnameseMobileAndLandline(String phone) {
        assertThat(UserImportValidator.validate(row("Nguyễn O'An", phone)).command().phone()).startsWith("0");
    }

    @ParameterizedTest @ValueSource(strings={"", "abc", "0123456789", "+12025550123", "091234567", "09123456789"})
    void rejectsInvalidPhones(String phone) {
        assertThatThrownBy(() -> UserImportValidator.validate(row("Nguyễn An", phone))).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest @ValueSource(strings={"", " ", "A"})
    void rejectsShortTrimmedNames(String name) {
        assertThatThrownBy(() -> UserImportValidator.validate(row(name, "0912345678"))).hasMessageContaining("Họ tên");
    }

    @Test void checksNameBoundariesAndKeepsHtmlAsData() {
        assertThat(UserImportValidator.validate(row("An", "0912345678")).command().fullName()).isEqualTo("An");
        assertThat(UserImportValidator.validate(row("A".repeat(150), "0912345678")).command().fullName()).hasSize(150);
        assertThatThrownBy(() -> UserImportValidator.validate(row("A".repeat(151), "0912345678"))).hasMessageContaining("Họ tên");
        assertThat(UserImportValidator.validate(row("<script>hi</script>", "0912345678")).command().fullName()).contains("<script>");
    }

    @Test void rejectsExtraColumnsAndMissingRequiredCells() {
        var extra = new ArrayList<>(row("Nguyễn An", "0912345678")); extra.add("ADMIN");
        assertThatThrownBy(() -> UserImportValidator.validate(extra)).hasMessageContaining("cột ngoài");
        assertThatThrownBy(() -> UserImportValidator.validate(List.of("abc"))).isInstanceOf(IllegalArgumentException.class);
        var missing = new ArrayList<>(row("Nguyễn An", "0912345678")); missing.set(4, " , ");
        assertThatThrownBy(() -> UserImportValidator.validate(missing)).hasMessageContaining("vai trò");
    }

    @Test void rejectsBadUsernameAndEmail() {
        var bad = new ArrayList<>(row("Nguyễn An", "0912345678")); bad.set(0, "bad name"); bad.set(1, "email");
        assertThatThrownBy(() -> UserImportValidator.validate(bad)).hasMessageContaining("Tên đăng nhập").hasMessageContaining("Email");
    }
}
