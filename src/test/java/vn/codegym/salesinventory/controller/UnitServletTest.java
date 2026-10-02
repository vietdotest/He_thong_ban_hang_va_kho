package vn.codegym.salesinventory.controller;
import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.*;
import vn.codegym.salesinventory.service.*;
import vn.codegym.salesinventory.validation.FieldValidationException;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class UnitServletTest extends CatalogControllerTestSupport {
    UnitService service;ProductService products;UnitServlet servlet;
    @BeforeEach void controller()throws Exception{service=mock(UnitService.class);products=mock(ProductService.class);servlet=new UnitServlet(service,products);init(servlet);params.putAll(Map.of("product","1","warehouse","1","name"," Thùng ","factor","24"));}
    @Test void saveUsesOwnerAndRedirects303()throws Exception{servlet.doPost(request,response);verify(service).save(12,0,1,"Thùng",new BigDecimal("24.000000"),1,0);verify(response).setStatus(303);}
    @Test void invalidFactorIsFieldErrorAndRawTextIsRetained()throws Exception{params.put("factor","not-a-number");servlet.doPost(request,response);verify(response).setStatus(400);assertThat(((Map<?,?>)attributes.get("edit")).get("factor")).isEqualTo("not-a-number");assertThat(((Map<?,?>)attributes.get("errors")).get("factor")).isNotNull();verify(service,never()).save(anyLong(),anyLong(),anyLong(),anyString(),any(),anyLong(),anyLong());}
    @Test void duplicateFieldErrorKeepsRawNameAndFactor()throws Exception{doThrow(FieldValidationException.field("name","Trùng")).when(service).save(anyLong(),anyLong(),anyLong(),anyString(),any(),anyLong(),anyLong());servlet.doPost(request,response);verify(response).setStatus(400);assertThat(((Map<?,?>)attributes.get("edit")).get("name")).isEqualTo(" Thùng ");}
    @Test void permissionAndCsrfAreEnforced()throws Exception{noPermissions();servlet.doPost(request,response);verify(response).sendError(403);verifyNoInteractions(service,products);}
    @Test void wrongCsrfCannotSave()throws Exception{params.put("_csrf","wrong");servlet.doPost(request,response);verify(response).sendError(403);verifyNoInteractions(service,products);}
    @Test void systemFailureIsNotConvertedToInputError()throws Exception{doThrow(new IllegalStateException("database down")).when(service).save(anyLong(),anyLong(),anyLong(),anyString(),any(),anyLong(),anyLong());servlet.doPost(request,response);verify(response).sendError(500);}
    @Test void invalidConversionQuantityIs400AndNamedFieldError()throws Exception{params.putAll(Map.of("id","2","action","convert","quantity","wrong"));servlet.doGet(request,response);verify(response).setStatus(400);assertThat(((Map<?,?>)attributes.get("errors")).get("quantity")).isNotNull();verify(service,never()).convert(anyLong(),anyLong(),any());}
}
