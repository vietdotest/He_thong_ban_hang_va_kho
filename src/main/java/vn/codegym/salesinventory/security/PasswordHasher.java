package vn.codegym.salesinventory.security;

public interface PasswordHasher {
    boolean matches(String rawPassword, String encodedPassword);

    String hash(String rawPassword);
}
