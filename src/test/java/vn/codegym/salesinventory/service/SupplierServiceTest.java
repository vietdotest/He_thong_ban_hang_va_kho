package vn.codegym.salesinventory.service;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class SupplierServiceTest {
    SupplierService.Input in(String code,String name,String tax,String phone){return new SupplierService.Input(code,name,tax,"Liên hệ",phone,"Thanh toán ngay",1,"ACTIVE",0);}
    @Test void validTaxFormatsAndVietnamPhones(){for(String tax:new String[]{"0123456789","0123456789001","0123456789-001"})assertThat(SupplierService.errors(in(" SUP-1 ","Nhà cung cấp",tax,"+84912345678"))).isEmpty();}
    @Test void reportsAllInvalidFields(){assertThat(SupplierService.errors(new SupplierService.Input("!"," ","abc","","abc","",0,"bad",-1))).containsKeys("code","name","taxCode","contact","phone","terms","warehouse","status","form");}
    @Test void boundariesAndHtmlNames(){assertThat(SupplierService.errors(in("A".repeat(50),"N".repeat(200),"0123456789","02412345678"))).isEmpty();assertThat(SupplierService.errors(in("A".repeat(51),"N".repeat(201),"123","012345678"))).containsKeys("code","name","taxCode","phone");assertThat(SupplierService.errors(in("A","<script>O'An</script>","0123456789","0912345678"))).isEmpty();}
}
