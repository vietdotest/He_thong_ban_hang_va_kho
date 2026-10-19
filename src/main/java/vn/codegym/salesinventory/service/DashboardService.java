package vn.codegym.salesinventory.service;

import java.util.*;
import vn.codegym.salesinventory.security.Access;

/**
 * S1-01, S1-06: Chọn tiêu đề và lối tắt công việc theo quyền của người đăng nhập.
 */
public final class DashboardService {

    // Chứa tên, đường dẫn và mô tả một lối tắt công việc.
    public record WorkArea(String label, String path, String description) {}

    private DashboardService() {}

    // Chọn các lối tắt công việc mà người dùng được phép truy cập.
    public static List<WorkArea> areas(Access access) {
        List<WorkArea> results = new ArrayList<>();
        if (access.allows("USER_MANAGE")) {
            results.add(
                    new WorkArea(
                            "Cấp tài khoản cho nhân sự",
                            "/admin/users",
                            "Cấp tài khoản và phân công nhân sự."));
        }
        if (access.allows("ROLE_MANAGE")) {
            results.add(
                    new WorkArea("Phân quyền", "/admin/roles", "Khai báo quyền cho bảy vai trò."));
        }
        if (access.allows("PRODUCT_MANAGE")) {
            results.add(
                    new WorkArea(
                            "Quản lý sản phẩm",
                            "/catalog/products",
                            "Quản lý mã hàng, nhóm hàng và quy cách."));
        } else if (access.allows("CATALOG_READ")) {
            results.add(
                    new WorkArea(
                            "Tra cứu hàng hóa",
                            "/catalog/products",
                            "Xem thông tin hàng hóa được phép truy cập."));
        }
        if (access.allows("WAREHOUSE_MANAGE")) {
            results.add(
                    new WorkArea(
                            "Đơn vị quy đổi",
                            "/catalog/units",
                            "Quản lý quy đổi trong phạm vi kho phụ trách."));
            results.add(
                    new WorkArea(
                            "Nhà cung cấp",
                            "/catalog/suppliers",
                            "Theo dõi nguồn hàng của kho phụ trách."));
        }
        if (access.allows("PRICE_MANAGE")) {
            results.add(
                    new WorkArea(
                            "Quản lý bảng giá",
                            "/pricing/lists",
                            "Khai báo giá bán theo nhóm khách hàng và thời gian."));
        } else if (access.allows("PRICE_READ")) {
            results.add(
                    new WorkArea(
                            "Xem bảng giá", "/pricing/lists", "Xem bảng giá đang có hiệu lực."));
        }
        if (access.allows("AUDIT_READ")) {
            results.add(
                    new WorkArea(
                            "Nhật ký thao tác",
                            "/admin/audit",
                            "Theo dõi người thực hiện và thay đổi dữ liệu."));
        }
        var priority =
                List.of(
                        "/admin/users",
                        "/catalog/products",
                        "/pricing/lists",
                        "/catalog/units",
                        "/catalog/suppliers",
                        "/admin/roles",
                        "/admin/audit");
        return results.stream()
                .sorted(Comparator.comparingInt(area -> priority.indexOf(area.path())))
                .limit(3)
                .toList();
    }

    // Chọn tiêu đề trang chủ theo các vai trò của người dùng.
    public static String heading(Access access) {
        if (access.roles().size() > 1) {
            return "Công việc theo các vai trò của bạn";
        }
        return switch (access.roles().stream().findFirst().orElse("")) {
            case "ADMIN" -> "Quản trị hệ thống";
            case "SALES_MANAGER" -> "Quản lý kinh doanh";
            case "WAREHOUSE", "WAREHOUSE_MANAGER" -> "Công việc kho";
            case "ACCOUNTANT" -> "Công việc kế toán";
            case "DIRECTOR" -> "Tổng quan điều hành";
            default -> "Công việc kinh doanh";
        };
    }
}
