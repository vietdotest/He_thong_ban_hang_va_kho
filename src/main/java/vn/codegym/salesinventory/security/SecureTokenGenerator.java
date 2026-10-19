package vn.codegym.salesinventory.security;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * S1-03, S1-08: Sinh mã ngẫu nhiên an toàn cho các liên kết xác nhận.
 */
public final class SecureTokenGenerator {

    private static final int TOKEN_BYTES = 32;

    private final SecureRandom secureRandom = new SecureRandom();

    // Sinh giá trị ngẫu nhiên dùng cho bước xác nhận.
    public String generate() {
        byte[] bytes = new byte[TOKEN_BYTES];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
