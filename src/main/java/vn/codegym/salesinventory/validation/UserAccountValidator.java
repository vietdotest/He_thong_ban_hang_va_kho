package vn.codegym.salesinventory.validation;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;
import vn.codegym.salesinventory.dto.UserAccountCommand;

public final class UserAccountValidator {
    private static final Pattern USERNAME = Pattern.compile("[A-Za-z0-9._-]{3,64}");
    private static final Pattern EMAIL = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");
    private static final Pattern PHONE = Pattern.compile("0[0-9]{9}");

    public Map<String, String> validate(UserAccountCommand command) {
        Map<String, String> errors = new LinkedHashMap<>();
        if (!USERNAME.matcher(command.username()).matches()) {
            errors.put("username", "Tên đăng nhập gồm 3–64 ký tự: chữ, số, dấu chấm, gạch dưới hoặc gạch ngang.");
        }
        if (command.fullName().length() < 2 || command.fullName().length() > 150) {
            errors.put("fullName", "Họ tên phải có từ 2 đến 150 ký tự.");
        }
        if (command.email().length() > 254 || !EMAIL.matcher(command.email()).matches()) {
            errors.put("email", "Email không đúng định dạng.");
        }
        if (!PHONE.matcher(command.normalizedPhone()).matches()) {
            errors.put("phone", "Số điện thoại phải gồm 10 chữ số và bắt đầu bằng 0.");
        }
        if (command.roleCode().isBlank()) {
            errors.put("roleCode", "Vui lòng chọn vai trò.");
        }
        return errors;
    }
}
