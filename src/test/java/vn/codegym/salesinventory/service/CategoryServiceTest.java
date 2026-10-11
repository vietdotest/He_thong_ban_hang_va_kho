package vn.codegym.salesinventory.service;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class CategoryServiceTest {
    @Test void validatesTrimmedNameAndCodeBoundaries() {
        assertThat(CategoryService.errors(" C_1 "," Tên Việt O'An ",null,0)).isEmpty();
        assertThat(CategoryService.errors("C".repeat(50),"N".repeat(150),1L,0)).isEmpty();
        assertThat(CategoryService.errors("C".repeat(51),"N".repeat(151),null,0)).containsKeys("code","name");
    }
    @Test void blankNullAndOneCharacterNamesAreRejectedTogether() {
        for(String name:new String[]{null,"","   "," x "})assertThat(CategoryService.errors("C",name,null,0)).containsKey("name");
        assertThat(CategoryService.errors(null,null,-1L,-1)).containsKeys("code","name","parent","form");
    }
    @Test void codeCannotContainHtmlOrPunctuationOutsideExistingPolicy() {for(String code:new String[]{"<script>","C.D","C D","Đ"})assertThat(CategoryService.errors(code,"Tên",null,0)).containsKey("code");}
}
