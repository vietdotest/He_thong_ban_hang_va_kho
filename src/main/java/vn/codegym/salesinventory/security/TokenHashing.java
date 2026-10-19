package vn.codegym.salesinventory.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * S1-03, S1-08: Băm mã xác nhận trước khi lưu để không lưu mã gốc trong cơ sở dữ liệu.
 */
public final class TokenHashing {

    private TokenHashing() {}

    // Băm mã xác nhận bằng SHA-256 trước khi lưu hoặc đối chiếu.
    public static String sha256(String value) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }
}
