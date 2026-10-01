package vn.codegym.salesinventory.service;
import org.junit.jupiter.api.Test;import java.util.*;import java.time.*;import static org.assertj.core.api.Assertions.*;
class XlsxTest {
 @Test void roundTripPreservesPhoneUnicodeAndFiveThousandRows(){List<List<String>> rows=new ArrayList<>();rows.add(List.of("SKU","Tên","Điện thoại"));for(int i=0;i<5000;i++)rows.add(List.of("SP"+i,"Hàng <&> Việt","0901234567"));var result=Xlsx.read(Xlsx.write(rows));assertThat(result).hasSize(5001);assertThat(result.get(5000).cells()).containsExactly("SP4999","Hàng <&> Việt","0901234567");}
 @Test void refusesNonWorkbookAndOversize(){assertThatThrownBy(()->Xlsx.read("text".getBytes())).isInstanceOf(IllegalArgumentException.class);assertThatThrownBy(()->Xlsx.read(new byte[Xlsx.MAX_BYTES+1])).isInstanceOf(IllegalArgumentException.class);}
 @Test void previewBoundToOwnerExpiryAndOneConfirmation(){var now=Instant.now();var p=new ImportPreview(1,List.of(),now);assertThatThrownBy(()->p.claim(2,p.getToken(),now)).isInstanceOf(IllegalArgumentException.class);p.claim(1,p.getToken(),now);assertThatThrownBy(()->p.claim(1,p.getToken(),now)).isInstanceOf(IllegalArgumentException.class);var expired=new ImportPreview(1,List.of(),now);assertThatThrownBy(()->expired.claim(1,expired.getToken(),now.plusSeconds(1800))).isInstanceOf(IllegalArgumentException.class);}
}
