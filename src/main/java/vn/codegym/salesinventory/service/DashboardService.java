package vn.codegym.salesinventory.service;
import java.util.*;
import vn.codegym.salesinventory.security.Access;
public final class DashboardService {
    public record WorkArea(String label,String path,String description) { }
    private DashboardService() { }
    public static List<WorkArea> areas(Access a) {
        List<WorkArea> out=new ArrayList<>();
        if(a.allows("USER_MANAGE")) out.add(new WorkArea("Quản trị người dùng","/admin/users","Cấp tài khoản và phân công nhân sự."));
        if(a.allows("ROLE_MANAGE")) out.add(new WorkArea("Phân quyền","/admin/roles","Khai báo quyền cho bảy vai trò."));
        if(a.allows("PRODUCT_MANAGE")) out.add(new WorkArea("Quản lý sản phẩm","/catalog/products","Quản lý mã hàng, nhóm hàng và quy cách."));
        else if(a.allows("CATALOG_READ")) out.add(new WorkArea("Tra cứu sản phẩm","/catalog/products","Xem thông tin hàng hóa được phép truy cập."));
        if(a.allows("WAREHOUSE_MANAGE")) { out.add(new WorkArea("Đơn vị quy đổi","/catalog/units","Quản lý quy đổi trong phạm vi kho phụ trách."));out.add(new WorkArea("Nhà cung cấp","/catalog/suppliers","Theo dõi nguồn hàng của kho phụ trách.")); }
        if(a.allows("PRICE_MANAGE")) out.add(new WorkArea("Quản lý bảng giá","/pricing/lists","Khai báo giá bán theo nhóm khách hàng và thời gian."));
        else if(a.allows("PRICE_READ")) out.add(new WorkArea("Tra cứu giá bán","/pricing/lists","Xem bảng giá đang có hiệu lực."));
        if(a.allows("AUDIT_READ")) out.add(new WorkArea("Nhật ký thao tác","/admin/audit","Theo dõi người thực hiện và thay đổi dữ liệu."));
        return List.copyOf(out);
    }
    public static String heading(Access a) {
        if(a.roles().size()>1) return "Công việc theo các vai trò của bạn";
        return switch(a.roles().stream().findFirst().orElse("")) {
            case "ADMIN" -> "Quản trị hệ thống";
            case "SALES_MANAGER" -> "Quản lý kinh doanh";
            case "WAREHOUSE","WAREHOUSE_MANAGER" -> "Công việc kho";
            case "ACCOUNTANT" -> "Công việc kế toán";
            case "DIRECTOR" -> "Tổng quan điều hành";
            default -> "Công việc kinh doanh";
        };
    }
}
