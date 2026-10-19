package vn.codegym.salesinventory.controller;

/**
 * S4-10: Dùng luồng đơn hàng cho người đăng nhập từ cổng đại lý.
 */
public final class DealerPortalServlet extends OrderServlet {

    @Override
    protected boolean portalMode() {
        return true;
    }
}
