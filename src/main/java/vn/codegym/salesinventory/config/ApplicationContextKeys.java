package vn.codegym.salesinventory.config;

public final class ApplicationContextKeys {
    public static final String DATA_SOURCE = "app.dataSource";
    public static final String AUTHENTICATION_SERVICE = "app.authenticationService";
    public static final String CSRF_TOKEN_MANAGER = "app.csrfTokenManager";
    public static final String SESSION_SERVICE = "app.sessionService";
    public static final String PASSWORD_RESET_SERVICE = "app.passwordResetService";
    public static final String PASSWORD_CHANGE_SERVICE = "app.passwordChangeService";
    public static final String USER_MANAGEMENT_SERVICE = "app.userManagementService";

    private ApplicationContextKeys() {
    }
}
