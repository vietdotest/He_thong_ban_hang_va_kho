package vn.codegym.salesinventory.validation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import vn.codegym.salesinventory.dto.UserAccountCommand;
import vn.codegym.salesinventory.model.UserStatus;

class UserAccountValidatorTest {
    private final UserAccountValidator validator = new UserAccountValidator();

    @Test
    void acceptsValidVietnameseUserDataAndNormalizesPhone() {
        UserAccountCommand command = new UserAccountCommand(
                " lan.nguyen ", " LAN@EXAMPLE.COM ", " Nguyễn Lan ", "+84 901-234-567",
                " sales ", UserStatus.ACTIVE, 0);

        assertThat(validator.validate(command)).isEmpty();
        assertThat(command.normalizedUsername()).isEqualTo("lan.nguyen");
        assertThat(command.normalizedEmail()).isEqualTo("lan@example.com");
        assertThat(command.normalizedPhone()).isEqualTo("0901234567");
        assertThat(command.roleCode()).isEqualTo("SALES");
    }

    @Test
    void reportsEveryInvalidField() {
        UserAccountCommand command = new UserAccountCommand(
                "x", "not-an-email", "A", "123", "", UserStatus.ACTIVE, 0);

        Map<String, String> errors = validator.validate(command);

        assertThat(errors).containsKeys("username", "email", "fullName", "phone", "roleCode");
    }
}
