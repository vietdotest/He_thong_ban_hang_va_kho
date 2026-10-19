package vn.codegym.salesinventory.security;

import org.mindrot.jbcrypt.BCrypt;

/**
 * S1-01, S1-03, S1-04: Băm và đối chiếu mật khẩu bằng BCrypt.
 */
public final class BCryptPasswordHasher implements PasswordHasher {

    // Đối chiếu mật khẩu nhập vào với giá trị đã băm.
    @Override
    public boolean matches(String rawPassword, String encodedPassword) {
        if (rawPassword == null || encodedPassword == null) {
            return false;
        }
        return BCrypt.checkpw(rawPassword, encodedPassword);
    }

    // Băm giá trị đầu vào theo thuật toán đang dùng.
    @Override
    public String hash(String rawPassword) {
        return BCrypt.hashpw(rawPassword, BCrypt.gensalt(12));
    }
}
