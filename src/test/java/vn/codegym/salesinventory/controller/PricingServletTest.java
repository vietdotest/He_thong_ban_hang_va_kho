package vn.codegym.salesinventory.controller;
import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.*;
import vn.codegym.salesinventory.service.*;
import vn.codegym.salesinventory.validation.FieldValidationException;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class PricingServletTest extends CatalogControllerTestSupport {
    PricingService service;ProductService products;PricingServlet servlet;
    @BeforeEach void controller()throws Exception{service=mock(PricingService.class);products=mock(ProductService.class);servlet=new PricingServlet(service,products);init(servlet);params.putAll(Map.of("action","item","id","1","revision","4","product","2","selling","100","floor","90"));}
    @Test void priceRequestPassesExpectedRevisionAndSessionActor()throws Exception{servlet.doPost(request,response);verify(service).saveItem(12,1,2,new BigDecimal("100.0000"),new BigDecimal("90.0000"),4);verify(response).setStatus(303);}
    @Test void missingRevisionCannotUseLegacyOverload()throws Exception{params.remove("revision");servlet.doPost(request,response);verify(response).setStatus(400);verify(service,never()).saveItem(anyLong(),anyLong(),anyLong(),any(),any(),anyLong());assertThat(((Map<?,?>)attributes.get("errors")).get("revision")).isNotNull();}
    @Test void invalidPriceRetainsBothRawValues()throws Exception{params.put("selling","<script>bad</script>");servlet.doPost(request,response);verify(response).setStatus(400);assertThat(((Map<?,?>)attributes.get("form")).get("selling")).isEqualTo("<script>bad</script>");assertThat(((Map<?,?>)attributes.get("form")).get("floor")).isEqualTo("90");assertThat(((Map<?,?>)attributes.get("errors")).get("selling")).isNotNull();}
    @Test void staleVersionIs400WithoutSilentlyReplacingRevision()throws Exception{doThrow(FieldValidationException.field("form","Đã thay đổi")).when(service).saveItem(anyLong(),anyLong(),anyLong(),any(),any(),anyLong());servlet.doPost(request,response);verify(response).setStatus(400);assertThat(((Map<?,?>)attributes.get("form")).get("revision")).isEqualTo("4");}
    @Test void deleteAlsoRequiresRevision()throws Exception{params.put("action","deleteItem");params.put("item","8");servlet.doPost(request,response);verify(service).deleteItem(12,1,8,4);}
    @Test void csrfAndPermissions403()throws Exception{params.put("_csrf","wrong");servlet.doPost(request,response);verify(response).sendError(403);verifyNoInteractions(service,products);}
    @Test void revokedRights403()throws Exception{noPermissions();servlet.doPost(request,response);verify(response).sendError(403);verifyNoInteractions(service,products);}
    @Test void systemFailure500()throws Exception{doThrow(new IllegalStateException("database")).when(service).saveItem(anyLong(),anyLong(),anyLong(),any(),any(),anyLong());servlet.doPost(request,response);verify(response).sendError(500);}
    @Test void badDatesAreNamedFieldErrors()throws Exception{params.putAll(Map.of("action","create","group","1","name"," <script>bad</script> ","from","wrong","to","2026-10-03"));params.remove("id");servlet.doPost(request,response);verify(response).setStatus(400);assertThat(((Map<?,?>)attributes.get("errors")).get("from")).isNotNull();}
}
