package vn.codegym.salesinventory.security;

/**
 * S1-02: Tập trung tên khóa lưu thông tin xác thực trong phiên HTTP.
 */
public final class SessionKeys {

    public static final String CURRENT_USER = "auth.currentUser";

    public static final String CSRF_TOKEN = "security.csrfToken";

    public static final String SERVER_SESSION_ID = "auth.serverSessionId";

    private SessionKeys() {}
}
