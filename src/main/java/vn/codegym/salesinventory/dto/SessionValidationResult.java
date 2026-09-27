package vn.codegym.salesinventory.dto;

import vn.codegym.salesinventory.security.CurrentUser;

public record SessionValidationResult(boolean valid, CurrentUser currentUser, String serverSessionId) {
    public static SessionValidationResult valid(CurrentUser user, String serverSessionId) {
        return new SessionValidationResult(true, user, serverSessionId);
    }

    public static SessionValidationResult invalid() {
        return new SessionValidationResult(false, null, null);
    }
}
