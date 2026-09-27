package vn.codegym.salesinventory.security;

import org.mindrot.jbcrypt.BCrypt;

public final class BCryptPasswordHasher implements PasswordHasher {
    @Override
    public boolean matches(String rawPassword, String encodedPassword) {
        if (rawPassword == null || encodedPassword == null) {
            return false;
        }
        return BCrypt.checkpw(rawPassword, encodedPassword);
    }

    @Override
    public String hash(String rawPassword) {
        return BCrypt.hashpw(rawPassword, BCrypt.gensalt(12));
    }
}
