package vn.codegym.salesinventory.security;

public final class RoutePermissions {
    private RoutePermissions() { }
    public static boolean publicPath(String p) {
        return p.equals("/") || p.equals("/login") || p.equals("/health") || p.equals("/forgot-password") || p.equals("/reset-password") || p.equals("/activate") || p.startsWith("/assets/");
    }
    public static String required(String path,String method) {
        if(path.equals("/dashboard") || path.equals("/logout") || path.startsWith("/account/")) return "PROFILE";
        if(path.equals("/admin/roles")) return "ROLE_MANAGE";
        if(path.startsWith("/admin/users")) return "USER_MANAGE";
        if(path.equals("/admin/assignments") || path.equals("/admin/scopes")) return "USER_MANAGE";
        if(path.equals("/admin/audit")) return "AUDIT_READ";
        if(path.equals("/catalog/products/import")) return "PRODUCT_MANAGE";
        if(path.startsWith("/catalog/products")) return method.equals("GET") ? "CATALOG_READ" : "PRODUCT_MANAGE";
        if(path.equals("/catalog/categories")) return method.equals("GET") ? "CATALOG_READ" : "PRODUCT_MANAGE";
        if(path.equals("/catalog/units") || path.equals("/catalog/suppliers")) return method.equals("GET") ? "CATALOG_READ" : "WAREHOUSE_MANAGE";
        if(path.startsWith("/pricing/")) return method.equals("GET") ? "PRICE_READ" : "PRICE_MANAGE";
        return null;
    }
}
