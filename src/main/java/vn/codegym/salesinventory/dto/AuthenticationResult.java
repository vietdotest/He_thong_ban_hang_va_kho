package vn.codegym.salesinventory.dto;

import vn.codegym.salesinventory.security.CurrentUser;

/**
 * S1-01: Trả kết quả đăng nhập cùng thông tin người dùng nếu thành công.
 */
public record AuthenticationResult(boolean authenticated, CurrentUser currentUser) {

    public static AuthenticationResult success(CurrentUser currentUser) {
        return new AuthenticationResult(true, currentUser);
    }

    public static AuthenticationResult failure() {
        return new AuthenticationResult(false, null);
    }
}
