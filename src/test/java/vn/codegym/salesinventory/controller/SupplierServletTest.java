package vn.codegym.salesinventory.controller;
import java.util.Map;
import org.junit.jupiter.api.*;
import vn.codegym.salesinventory.service.SupplierService;
import vn.codegym.salesinventory.validation.FieldValidationException;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class SupplierServletTest extends CatalogControllerTestSupport {
    SupplierService service;SupplierServlet servlet;
    @BeforeEach void controller()throws Exception{service=mock(SupplierService.class);when(service.search(anyLong(),anyString(),any())).thenReturn(new vn.codegym.salesinventory.dto.PageResult<>(java.util.List.of(),0,1,20));servlet=new SupplierServlet(service);init(servlet);params.putAll(Map.of("code","SUP","name"," Nhà cung cấp ","taxCode","0123456789","contact","O'An","phone","0912345678","terms","Ngay","warehouse","1","status","ACTIVE"));}
    @Test void savePreservesEncodedFilterAndValidPaging()throws Exception{params.putAll(Map.of("q","O'An & kho","page","2","pageSize","50"));servlet.doPost(request,response);verify(response).setHeader("Location","/catalog/suppliers?q=O%27An+%26+kho&page=2&pageSize=50&notice=saved");}
    @Test void saveUsesSessionAnd303()throws Exception{params.put("userId","999");servlet.doPost(request,response);verify(service).save(eq(12L),eq(0L),any());verify(response).setStatus(303);}
    @Test void fieldErrorKeepsAllRawValues()throws Exception{doThrow(FieldValidationException.field("phone","Sai")).when(service).save(anyLong(),anyLong(),any());params.put("phone","<script>x</script>");servlet.doPost(request,response);verify(response).setStatus(400);assertThat(((Map<?,?>)attributes.get("edit")).get("name")).isEqualTo(" Nhà cung cấp ");assertThat(((Map<?,?>)attributes.get("edit")).get("contact_phone")).isEqualTo("<script>x</script>");assertThat(((Map<?,?>)attributes.get("errors")).get("phone")).isEqualTo("Sai");}
    @Test void invalidWarehouseIs400()throws Exception{params.put("warehouse","abc");servlet.doPost(request,response);verify(response).setStatus(400);verify(service,never()).save(anyLong(),anyLong(),any());assertThat(((Map<?,?>)attributes.get("errors")).get("warehouse")).isEqualTo("Hãy chọn kho hợp lệ.");assertThat(((Map<?,?>)attributes.get("edit")).get("warehouse_id")).isEqualTo("abc");}
    @Test void blankWarehouseKeepsFieldErrorAndDoesNotSave()throws Exception{params.put("warehouse","");servlet.doPost(request,response);verify(response).setStatus(400);verify(service,never()).save(anyLong(),anyLong(),any());assertThat(((Map<?,?>)attributes.get("errors")).get("warehouse")).isEqualTo("Hãy chọn kho hợp lệ.");assertThat(((Map<?,?>)attributes.get("edit")).get("warehouse_id")).isEqualTo("");}
    @Test void csrf403()throws Exception{params.put("_csrf","wrong");servlet.doPost(request,response);verify(response).sendError(403);verifyNoInteractions(service);}
    @Test void permission403()throws Exception{noPermissions();servlet.doPost(request,response);verify(response).sendError(403);verifyNoInteractions(service);}
    @Test void databaseFailureIs500()throws Exception{doThrow(new IllegalStateException("database")).when(service).save(anyLong(),anyLong(),any());servlet.doPost(request,response);verify(response).sendError(500);}
}
