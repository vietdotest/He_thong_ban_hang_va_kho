package vn.codegym.salesinventory.service;

import java.util.*;
import org.junit.jupiter.api.Test;
import vn.codegym.salesinventory.security.Access;
import static org.assertj.core.api.Assertions.*;

class ImportReportTest {
    Access access(String role,String... permissions) {return new Access(Set.of(role),Set.of(permissions),List.of(),List.of(),List.of());}
    @Test void reportIsOwnedAndPermissionIsCheckedOnEveryDownload() {
        var report=new ImportReport(12,true,"nguoi-dung.xlsx",UserImportService.HEADERS,List.of(new ImportPreview.Line(2,List.of("nv01","nv@example.com","Tên","0901234567","SALES","","","PASSWORD_MUST_NOT_EXPORT","TOKEN_MUST_NOT_EXPORT"),"Tạo mới","")));
        var admin=access("ADMIN","USER_MANAGE");
        var rows=Xlsx.read(report.workbook(12,admin));
        assertThat(rows.get(0).cells()).contains("Dòng","Thao tác","Kết quả","Lỗi").doesNotContain("Mật khẩu","Token");
        assertThat(rows.get(1).cells()).contains("nv01","Đã nhập").doesNotContain("PASSWORD_MUST_NOT_EXPORT","TOKEN_MUST_NOT_EXPORT");
        assertThatThrownBy(()->report.workbook(13,admin)).isInstanceOf(SecurityException.class);
        assertThatThrownBy(()->report.workbook(12,access("ADMIN"))).isInstanceOf(SecurityException.class);
    }
    @Test void productCostDisappearsFromHtmlRowsAndWorkbookWhenCostReadIsRevoked() {
        var headers=new ArrayList<>(ProductImportService.HEADERS);headers.add("Giá vốn");
        var report=new ImportReport(12,false,"products.xlsx",headers,List.of(new ImportPreview.Line(2,List.of("SKU","Sản phẩm","NHOM","Lon","Thùng","ACTIVE","654321.1234"),"Cập nhật",""),new ImportPreview.Line(3,List.of("BAD"),"Tạo mới","Thiếu tên")));
        var manager=access("SALES_MANAGER","PRODUCT_MANAGE","COST_READ");
        assertThat(Xlsx.read(report.workbook(12,manager)).get(1).cells()).contains("654321.1234");
        var revoked=access("SALES_MANAGER","PRODUCT_MANAGE");
        assertThat(report.headers(12,revoked)).doesNotContain("Giá vốn");
        assertThat(report.lines(12,revoked).get(0).cells()).doesNotContain("654321.1234");
        var rows=Xlsx.read(report.workbook(12,revoked));assertThat(rows.get(0).cells()).doesNotContain("Giá vốn");
        assertThat(rows.get(1).cells()).doesNotContain("654321.1234").contains("Cập nhật");
        assertThat(rows.get(2).cells()).contains("Bỏ qua","Thiếu tên");
        var admin=access("ADMIN","PRODUCT_MANAGE","COST_READ");
        assertThat(report.headers(12,admin)).doesNotContain("Giá vốn");
    }
}
