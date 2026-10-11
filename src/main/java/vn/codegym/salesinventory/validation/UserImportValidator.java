package vn.codegym.salesinventory.validation;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import vn.codegym.salesinventory.dto.UserAccountCommand;
import vn.codegym.salesinventory.model.UserStatus;

public final class UserImportValidator {
    public record Input(UserAccountCommand command, Set<String> roles,
                        Set<String> warehouses, Set<String> territories) { }

    private UserImportValidator() { }

    public static Input validate(List<String> row) {
        if (row.size() > 7) throw new IllegalArgumentException("Dòng có cột ngoài tệp mẫu.");
        String phone = PhoneNumber.normalize(cell(row, 3));
        Set<String> roles = codes(cell(row, 4).toUpperCase(Locale.ROOT));
        var command = new UserAccountCommand(cell(row, 0), cell(row, 1), cell(row, 2), phone,
                roles.stream().findFirst().orElse(""), UserStatus.PENDING_ACTIVATION, 0);
        var errors = new UserAccountValidator().validate(command);
        // PhoneNumber đã kiểm tra cả số di động và số cố định Việt Nam.
        errors.remove("phone");
        if (!errors.isEmpty()) throw new IllegalArgumentException(String.join(" ", errors.values()));
        return new Input(command, roles, codes(cell(row, 5)), codes(cell(row, 6)));
    }

    private static String cell(List<String> row, int index) {
        return index < row.size() ? row.get(index) : "";
    }

    private static Set<String> codes(String text) {
        Set<String> result = new LinkedHashSet<>();
        for (String code : text.split(",")) if (!code.isBlank()) result.add(code.trim());
        return result;
    }
}
