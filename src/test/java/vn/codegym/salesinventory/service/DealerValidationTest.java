package vn.codegym.salesinventory.service;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class DealerValidationTest {
    @Test void validInputAndFieldErrors(){
        assertThat(DealerService.errors(new DealerService.Input("DL-01","Đại lý","0101234567-001","+84 912345678",1,1,1,null,"ACTIVE",0))).isEmpty();
        assertThat(DealerService.errors(new DealerService.Input("!","","abc","abc",0,0,0,-1L,"BAD",-1))).containsKeys("code","name","taxCode","phone","group","territory","staff","warehouse","status","form");
    }
}
