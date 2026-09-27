package vn.codegym.salesinventory.security;

import jakarta.servlet.http.HttpSession;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

public final class CsrfTokenManager {
    private static final int TOKEN_BYTES = 32;
    private final SecureRandom secureRandom;

    public CsrfTokenManager() {
        this(new SecureRandom());
    }

    CsrfTokenManager(SecureRandom secureRandom) {
        this.secureRandom = secureRandom;
    }

    public String getOrCreate(HttpSession session) {
        Object existing = session.getAttribute(SessionKeys.CSRF_TOKEN);
        if (existing instanceof String token && !token.isBlank()) {
            return token;
        }
        byte[] bytes = new byte[TOKEN_BYTES];
        secureRandom.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        session.setAttribute(SessionKeys.CSRF_TOKEN, token);
        return token;
    }

    public boolean isValid(HttpSession session, String submittedToken) {
        if (session == null || submittedToken == null) {
            return false;
        }
        Object expected = session.getAttribute(SessionKeys.CSRF_TOKEN);
        if (!(expected instanceof String expectedToken)) {
            return false;
        }
        return MessageDigest.isEqual(
                expectedToken.getBytes(StandardCharsets.UTF_8),
                submittedToken.getBytes(StandardCharsets.UTF_8)
        );
    }

    public void rotate(HttpSession session) {
        session.removeAttribute(SessionKeys.CSRF_TOKEN);
        getOrCreate(session);
    }
}
